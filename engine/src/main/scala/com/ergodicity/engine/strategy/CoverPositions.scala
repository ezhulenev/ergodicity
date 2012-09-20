package com.ergodicity.engine.strategy

import akka.actor._
import akka.dispatch.Await
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.duration._
import akka.util.{Duration, Timeout}
import com.ergodicity.core.PositionsTracking.GetPositions
import com.ergodicity.core.PositionsTracking.Positions
import com.ergodicity.core.order.OrderActor.OrderEvent
import com.ergodicity.core.position.Position
import com.ergodicity.core.session.InstrumentParameters.{OptionParameters, FutureParameters}
import com.ergodicity.core.session.{InstrumentParameters, InstrumentState}
import com.ergodicity.core.{Security, position}
import com.ergodicity.engine.StrategyEngine
import com.ergodicity.engine.service.Trading.Buy
import com.ergodicity.engine.service.Trading.OrderExecution
import com.ergodicity.engine.service.Trading.Sell
import com.ergodicity.engine.service.{Trading, Portfolio}
import com.ergodicity.engine.strategy.CoverPositionsState.RemainingPositions
import com.ergodicity.engine.strategy.InstrumentWatchDog._
import com.ergodicity.engine.strategy.Strategy.{Stop, Start}
import scala.Some
import scala.collection.{immutable, mutable}
import com.ergodicity.core.order.Fill
import com.ergodicity.engine.strategy.CoverPositions.CoverAll

object CoverAllPositions {
  implicit case object CoverAllPositions extends StrategyId

  def apply() = new StrategiesFactory {

    def strategies = (strategy _ :: Nil)

    def strategy(engine: StrategyEngine) = Props(new CoverPositions(engine) with CoverAll)
  }
}

sealed trait CoverPositionsState

object CoverPositionsState {

  case object Ready extends CoverPositionsState

  case object CoveringPositions extends CoverPositionsState

  case object PositionsCovered extends CoverPositionsState

  case class RemainingPositions(positions: immutable.Map[Security, Position] = Map()) {
    def coveredAll = positions.values.foldLeft(true)((b, a) => b && a == Position.flat)

    def fill(security: Security, amount: Int) = copy(positions = positions.transform {
      case (s, Position(pos)) if (s == security) =>
        if (pos > 0)
          Position(pos - amount)
        else
          Position(pos + amount)
      case (_, pos) => pos
    })
  }

}

object CoverPositions {
  trait CoverAll {
    def cover(position: Position) = position.dir != com.ergodicity.core.position.Flat
  }

  trait CoverShort {
    def cover(position: Position) = position.dir != com.ergodicity.core.position.Short
  }

  trait CoverLong {
    def cover(position: Position) = position.dir != com.ergodicity.core.position.Long
  }
}

abstract class CoverPositions(val engine: StrategyEngine)(implicit id: StrategyId) extends Strategy with Actor with LoggingFSM[CoverPositionsState, RemainingPositions] with InstrumentWatcher {

  import CoverPositionsState._

  val portfolio = engine.services(Portfolio.Portfolio)
  val trading = engine.services(Trading.Trading)

  // Configuration and implicits
  implicit object WatchDog extends WatchDogConfig(self, true, true, true)

  implicit val timeout = Timeout(1.second)

  implicit val executionContext = context.system

  // Positions that we are going to cover
  val positions: Map[Security, Position] = getOpenedPositions(5.seconds)

  // Catched instruments
  val parameters = mutable.Map[Security, InstrumentParameters]()
  val states = mutable.Map[Security, InstrumentState]()

  // Order executions
  val executions = mutable.Map[Security, OrderExecution]()

  override def preStart() {
    log.info("Started CoverPositions")
    log.debug("Going to cover positions = " + positions)
    engine.reportReady(positions)
  }

  startWith(Ready, RemainingPositions(positions))

  when(Ready) {
    case Event(Start, _) if (positions nonEmpty) =>
      log.info("Start strategy. Positions to cover = " + positions)
      positions.keys foreach watchInstrument
      goto(CoveringPositions)

    case Event(Start, _) if (positions isEmpty) =>
      log.info("Start strategy. No open positions to cover")
      goto(PositionsCovered)
  }

  when(CoveringPositions) {
    case Event(OrderEvent(order, Fill(amount, _, _)), remaining) if (executions.values.find(_.order == order).isDefined) =>
      val executionReport = executions.values.find(_.order == order).get
      val updated = remaining.fill(executionReport.security, amount)

      if (updated.coveredAll)
        goto(PositionsCovered)
      else
        stay() using updated
  }

  when(PositionsCovered) {
    case Event(Stop, _) => stop(FSM.Shutdown)
  }

  whenUnhandled {
    case Event(Catched(security, session, ref), _) =>
      log.info("Catched assigned instrument; Security = " + security + ", session = " + session)
      stay()

    case Event(CatchedState(security, state), _) =>
      states(security) = state
      tryCover(security)
      stay()

    case Event(CatchedParameters(security, params), _) =>
      parameters(security) = params
      tryCover(security)
      stay()

    case Event(execution: OrderExecution, _) =>
      executions(execution.security) = execution
      execution.subscribeOrderEvents(self)
      stay()
  }

  onTransition {
    case _ -> PositionsCovered => log.info("Initial positions covered")
  }

  private def tryCover(security: Security) {
    import scalaz.Scalaz._

    def sellPrice(parameters: InstrumentParameters) = parameters match {
      case FutureParameters(lastClQuote, limits) => lastClQuote - limits.lower
      case OptionParameters(lastClQuote) => failed("Option parameters no supported")
    }

    def buyPrice(parameters: InstrumentParameters) = parameters match {
      case FutureParameters(lastClQuote, limits) => lastClQuote + limits.upper
      case OptionParameters(lastClQuote) => failed("Option parameters no supported")
    }

    val p = parameters get security
    val s = states get security

    val tuple = (p |@| s) ((_, _))

    tuple match {
      case Some((params, InstrumentState.Online)) if (positions(security).dir == position.Long) =>
        (trading ? Sell(security, positions(security).pos.abs, sellPrice(params))) pipeTo self

      case Some((params, InstrumentState.Online)) if (positions(security).dir == position.Short) =>
        (trading ? Buy(security, positions(security).pos.abs, buyPrice(params))) pipeTo self

      case _ =>
    }
  }


  private def getOpenedPositions(atMost: Duration): Map[Security, Position] = {
    val future = (portfolio ? GetPositions).mapTo[Positions]
    Await.result(future, atMost).positions.filter(tuple => cover(tuple._2))
  }

  def cover(position: Position): Boolean
}
