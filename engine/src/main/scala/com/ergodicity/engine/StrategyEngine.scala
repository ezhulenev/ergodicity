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

  case class StrategyReady(id: StrategyId) extends StrategyNotification

  // Typed traits for strategies notifications
  trait EngineNotifier {
    def position(isin: Isin, position: Position)

    def ready()
  }

  class EngineConfig(engine: ActorRef) {
    def notifier(implicit id: StrategyId): EngineNotifier = new EngineNotifier {
      def position(isin: Isin, position: Position) {
        engine ! StrategyPosition(id, isin, position)
      }

      def ready() {
        engine ! StrategyReady(id)
      }
    }
  }

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

class StrategyEngine(factory: StrategiesFactory = StrategiesFactory.empty) extends Actor with LoggingFSM[StrategyEngineState, StrategyEngineData] {

  import StrategyEngineState._
  import StrategyEngineData._

  implicit object Config extends EngineConfig(self)

  protected[engine] val strategies = mutable.Map[StrategyId, ManagedStrategy]()

  startWith(Idle, Void)

  when(Idle) {
    case Event(StartStrategies, Void) =>
      factory.strategies foreach start
      log.info("Started strategies = " + strategies.keys)
      goto(Starting) using AwaitingReadiness(strategies.keys)
  }

  when(Starting) {
    case Event(StrategyReady(id), w@AwaitingReadiness(awaiting)) =>
      log.info("Strategy ready, Id = " + id)
      stay() using (w.copy(strategies = awaiting filterNot (_ == id)))
  }

  initialize

  private def start(builder: StrategyBuilder) {
    log.info("Start strategy, Id = " + builder.id)
    strategies(builder.id) = ManagedStrategy(context.actorOf(builder.props, builder.id.toString))
  }
}