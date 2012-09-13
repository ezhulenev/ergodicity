package com.ergodicity.engine.strategy

import akka.actor._
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.duration._
import akka.util.{Duration, Timeout}
import collection.mutable
import com.ergodicity.core.PositionsTracking.{GetPositions, Positions}
import com.ergodicity.core.position.Position
import com.ergodicity.core.session.{InstrumentState, Instrument}
import com.ergodicity.core.{session, Isin}
import com.ergodicity.engine.StrategyEngine
import com.ergodicity.engine.service.{Trading, Portfolio}
import com.ergodicity.engine.strategy.CloseAllPositionsState.Catching
import com.ergodicity.engine.strategy.InstrumentWatchDog.{CatchedState, Catched, WatchDogConfig}
import com.ergodicity.engine.strategy.Strategy.Start

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

  case object CatchingInstruments extends CloseAllPositionsState

  case object ClosingPositions extends CloseAllPositionsState

  case object PositionsClosed extends CloseAllPositionsState

  case class Catching(catching: Set[Isin], instruments: Map[Isin, Instrument] = Map(), states: Map[Isin, InstrumentState] = Map()) {
    def catched(isin: Isin, instrument: Instrument) = copy(instruments = instruments + (isin -> instrument))

    def catched(isin: Isin, state: InstrumentState) = copy(states = states + (isin -> state))

    def catchedAll = catching == instruments.keySet && catching == states.keySet && states.foldLeft(true)((b, state) => b && state == session.InstrumentState.Assigned )
  }

}

class CloseAllPositions(val engine: StrategyEngine)(implicit id: StrategyId) extends Strategy with Actor with FSM[CloseAllPositionsState, Catching] with InstrumentWatcher {

  import CloseAllPositionsState._

  val portfolio = engine.services(Portfolio.Portfolio)
  val broker = engine.services(Trading.Trading)

  // Configuration and implicits
  implicit object WatchDog extends WatchDogConfig(self, true, true)

  implicit val timeout = Timeout(1.second)

  implicit val executionContext = context.system

  // Positions that we are going to close
  val positions: Map[Isin, Position] = getOpenedPositions(5.seconds)

  override def preStart() {
    log.info("Started CloseAllPositions")
    log.debug("Going to close positions = " + positions)
    engine.reportReady(positions)
  }

  startWith(Ready, Catching(positions.keySet))

  when(Ready) {
    case Event(Start, _) =>
      positions.keys foreach watchInstrument
      goto(CatchingInstruments)
  }

  when(CatchingInstruments) {
    case Event(Catched(isin, session, instrument, ref), catching) =>
      val updated = catching.catched(isin, instrument)
      if (updated.catchedAll) goto(ClosingPositions) else stay() using updated

    case Event(CatchedState(isin, state), catching) =>
      val updated = catching.catched(isin, state)
      if (updated.catchedAll) goto(ClosingPositions) else stay() using updated
  }

  /*when(ClosingPositions) {
    case Event(ExecutionReport())

  }*/

  onTransition {
    case CatchingInstruments -> ClosingPositions =>
      log.debug("Catched all instruments, going to close positions")
  }


  private def getOpenedPositions(atMost: Duration): Map[Isin, Position] = {
    val future = (portfolio ? GetPositions).mapTo[Positions]
    Await.result(future, atMost).positions
  }
}
