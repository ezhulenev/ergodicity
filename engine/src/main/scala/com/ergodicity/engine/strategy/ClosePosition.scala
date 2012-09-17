package com.ergodicity.engine.strategy

import akka.actor._
import akka.dispatch.Await
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.duration._
import akka.util.{Duration, Timeout}
import com.ergodicity.core.PositionsTracking.GetPositions
import com.ergodicity.core.PositionsTracking.Positions
import com.ergodicity.core.order.FillOrder
import com.ergodicity.core.order.OrderActor.OrderEvent
import com.ergodicity.core.position.Position
import com.ergodicity.core.session.InstrumentParameters.FutureParameters
import com.ergodicity.core.session.{Instrument, InstrumentState}
import com.ergodicity.core.{Isin, position}
import com.ergodicity.engine.StrategyEngine
import com.ergodicity.engine.service.Trading.Buy
import com.ergodicity.engine.service.Trading.OrderExecution
import com.ergodicity.engine.service.Trading.Sell
import com.ergodicity.engine.service.{Trading, Portfolio}
import com.ergodicity.engine.strategy.CloseAllPositionsState.RemainingPositions
import com.ergodicity.engine.strategy.InstrumentWatchDog._
import com.ergodicity.engine.strategy.Strategy.{Stop, Start}
import scala.Some
import scala.collection.{immutable, mutable}

object CloseAllPositions {

  implicit case object CloseAllPositions extends StrategyId

  def apply() = new StrategiesFactory {

    def strategies = (strategy _ :: Nil)

    def strategy(engine: StrategyEngine) = Props(new CloseAllPositions(engine))
  }
}

sealed trait CloseAllPositionsState

object CloseAllPositionsState {

  case object Ready extends CloseAllPositionsState

  case object ClosingPositions extends CloseAllPositionsState

  case object PositionsClosed extends CloseAllPositionsState

  case class RemainingPositions(positions: immutable.Map[Isin, Position] = Map()) {
    def closedAll = positions.values.foldLeft(true)((b, a) => b && a == Position.flat)

    def fill(isin: Isin, amount: Int) = copy(positions = positions.transform {
      case (i, Position(pos)) if (i == isin) =>
        if (pos > 0)
          Position(pos - amount)
        else
          Position(pos + amount)
      case (_, pos) => pos
    })
  }

}

class CloseAllPositions(val engine: StrategyEngine)(implicit id: StrategyId) extends Strategy with Actor with LoggingFSM[CloseAllPositionsState, RemainingPositions] with InstrumentWatcher {

  import CloseAllPositionsState._

  val portfolio = engine.services(Portfolio.Portfolio)
  val trading = engine.services(Trading.Trading)

  // Configuration and implicits
  implicit object WatchDog extends WatchDogConfig(self, true, true, true)

  implicit val timeout = Timeout(1.second)

  implicit val executionContext = context.system

  // Positions that we are going to close
  val positions: Map[Isin, Position] = getOpenedPositions(5.seconds)

  // Catched instruments
  val instruments = mutable.Map[Isin, Instrument]()
  val states = mutable.Map[Isin, InstrumentState]()

  // Order executions
  val executions = mutable.Map[Isin, OrderExecution]()

  override def preStart() {
    log.info("Started CloseAllPositions")
    log.debug("Going to close positions = " + positions)
    engine.reportReady(positions)
  }

  startWith(Ready, RemainingPositions(positions))

  when(Ready) {
    case Event(Start, _) if (positions nonEmpty) =>
      log.info("Start strategy. Positions to close = " + positions)
      positions.keys foreach watchInstrument
      goto(ClosingPositions)

    case Event(Start, _) if (positions isEmpty) =>
      log.info("Start strategy. No open positions to close")
      goto(PositionsClosed)
  }

  when(ClosingPositions) {
    case Event(OrderEvent(order, FillOrder(price, amount)), remaining) if (executions.values.find(_.order == order).isDefined) =>
      val executionReport = executions.values.find(_.order == order).get
      val isin = executionReport.security.isin
      val updated = remaining.fill(isin, amount)

      if (updated.closedAll)
        goto(PositionsClosed)
      else
        stay() using updated
  }

  when(PositionsClosed) {
    case Event(Stop, _) => stop(FSM.Shutdown)
  }

  whenUnhandled {
    case Event(Catched(isin, session, security, ref), _) =>
      log.info("Catched assigned instrument; Isin = " + isin + ", session = " + session)
      stay()

    case Event(CatchedState(isin, state), _) =>
      states(isin) = state
      tryClose(isin)
      stay()

    case Event(CatchedInstrument(isin, instrument), _) =>
      instruments(isin) = instrument
      tryClose(isin)
      stay()

    case Event(execution: OrderExecution, _) =>
      executions(execution.security.isin) = execution
      execution.subscribeOrderEvents(self)
      stay()
  }

  onTransition {
    case _ -> PositionsClosed => log.info("Initial positions closed")
  }

  private def tryClose(isin: Isin) {
    import scalaz.Scalaz._


    val i = instruments get isin
    val s = states get isin


    val tuple = (i |@| s) ((_, _))

    tuple match {
      case Some((Instrument(_, security, FutureParameters(lastClQuote, limits)), InstrumentState.Online)) if (positions(isin).dir == position.Long) =>
        (trading ? Sell(security, positions(isin).pos.abs, lastClQuote - limits.lower)) pipeTo self

      case Some((Instrument(_, security, FutureParameters(lastClQuote, limits)), InstrumentState.Online)) if (positions(isin).dir == position.Short) =>
        (trading ? Buy(security, positions(isin).pos.abs, lastClQuote + limits.upper)) pipeTo self

      case Some(unsupported) => log.warning("Unsupported position = "+unsupported)

      case _ =>
    }
  }


  private def getOpenedPositions(atMost: Duration): Map[Isin, Position] = {
    val future = (portfolio ? GetPositions).mapTo[Positions]
    Await.result(future, atMost).positions.filterNot(_._2.dir == position.Flat)
  }
}
