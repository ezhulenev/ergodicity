package com.ergodicity.engine

import strategy.{StrategyBuilder, StrategiesFactory, StrategyId}
import akka.actor.{LoggingFSM, Actor, ActorRef}
import com.ergodicity.engine.StrategyEngine._
import collection.mutable
import com.ergodicity.core.position.Position
import com.ergodicity.core.Isin
import com.ergodicity.engine.StrategyEngine.ManagedStrategy

object StrategyEngine {

  case class ManagedStrategy(ref: ActorRef)

  // Engine messages
  case object StartStrategies

  case object StopStrategies

  // Messages from strategies
  sealed trait StrategyNotification {
    def id: StrategyId
  }

  case class StrategyPosition(id: StrategyId, isin: Isin, position: Position) extends StrategyNotification

  case class StrategyReady(id: StrategyId, positions: Map[Isin, Position]) extends StrategyNotification
}

sealed trait StrategyEngineState

object StrategyEngineState {

  case object Idle extends StrategyEngineState

  case object Starting extends StrategyEngineState

}

sealed trait StrategyEngineData

object StrategyEngineData {

  case object Void extends StrategyEngineData

  case class AwaitingReadiness(strategies: Iterable[StrategyId]) extends StrategyEngineData

}

abstract class StrategyEngine(factory: StrategiesFactory = StrategiesFactory.empty)
                             (implicit val services: Services) {
  engine: StrategyEngine with Actor =>

  protected val strategies = mutable.Map[StrategyId, ManagedStrategy]()

  def reportPosition(isin: Isin, position: Position)(implicit id: StrategyId) {
    self ! StrategyPosition(id, isin, position)
  }

  def reportReady(positions: Map[Isin, Position])(implicit id: StrategyId) {
    self ! StrategyReady(id, positions)
  }
}

class StrategyEngineActor(factory: StrategiesFactory = StrategiesFactory.empty)
                         (implicit services: Services) extends StrategyEngine(factory)(services) with Actor with LoggingFSM[StrategyEngineState, StrategyEngineData] {

  import StrategyEngineState._
  import StrategyEngineData._

  private val positions = mutable.Map[(StrategyId, Isin), Position]()

  startWith(Idle, Void)

  when(Idle) {
    case Event(StartStrategies, Void) =>
      factory.strategies foreach start
      log.info("Started strategies = " + strategies.keys)
      goto(Starting) using AwaitingReadiness(strategies.keys)
  }

  when(Starting) {
    case Event(StrategyReady(id, strategyPositions), w@AwaitingReadiness(awaiting)) =>
      log.info("Strategy ready, Id = " + id + ", positions = " + positions)
      strategyPositions.foreach {
        case (isin, position) =>
          positions(id -> isin) = position
      }
      stay() using (w.copy(strategies = awaiting filterNot (_ == id)))
  }

  whenUnhandled {
    case Event(pos@StrategyPosition(id, isin, position), _) =>
      positions(id -> isin) = position
      stay()
  }

  initialize

  private def start(builder: StrategyBuilder) {
    log.info("Start strategy, Id = " + builder.id)
    strategies(builder.id) = ManagedStrategy(context.actorOf(builder.props(this), builder.id.toString))
  }
}
