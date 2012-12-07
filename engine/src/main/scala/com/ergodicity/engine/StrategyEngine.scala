package com.ergodicity.engine

import akka.actor.{FSM, LoggingFSM, Actor, ActorRef}
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import akka.util.duration._
import collection.immutable
import collection.mutable
import com.ergodicity.core.PositionsTracking.{Positions, GetPositions}
import com.ergodicity.core.Security
import com.ergodicity.core.position.Position
import com.ergodicity.engine.StrategyEngine._
import com.ergodicity.engine.strategy.{Strategy, StrategyBuilder, StrategiesFactory, StrategyId}
import scalaz.Scalaz._
import service.Portfolio.Portfolio

object StrategyEngine {

  case class ManagedStrategy(ref: ActorRef)

  // Engine messages
  case object LoadStrategies

  case object StartStrategies

  case object StopStrategies

  // Messages from strategies
  sealed trait StrategyNotification {
    def id: StrategyId
  }

  case class StrategyPosition(id: StrategyId, security: Security, position: Position) extends StrategyNotification

  case class StrategyReady(id: StrategyId, positions: Map[Security, Position]) extends StrategyNotification

  case class StrategyStarted(id: StrategyId) extends StrategyNotification

  case class StrategyStopped(id: StrategyId, positions: Map[Security, Position]) extends StrategyNotification

  // Positions Reconciliation
  sealed trait Reconciliation

  case object Reconciled extends Reconciliation

  case class Mismatched(mismatches: Iterable[Mismatch]) extends Reconciliation

  case class Mismatch(security: Security, portfolioPosition: Position, strategiesPosition: Position, strategiesAllocation: Map[StrategyId, Position])

  class ReconciliationFailed(mismatches: Iterable[Mismatch]) extends RuntimeException("Reconciliation failed; Mismatches size = " + mismatches.size)

  class StrategyFailedException(stragety: StrategyId, message: String) extends RuntimeException(message)

}

sealed trait StrategyEngineState

object StrategyEngineState {

  case object Idle extends StrategyEngineState

  case object Loading extends StrategyEngineState

  case object Reconciling extends StrategyEngineState

  case object StrategiesReady extends StrategyEngineState

  case object Starting extends StrategyEngineState

  case object Active extends StrategyEngineState

  case object Stopping extends StrategyEngineState

}

sealed trait StrategyEngineData

object StrategyEngineData {

  case object Void extends StrategyEngineData

  case class PendingStrategies(strategies: Iterable[StrategyId]) extends StrategyEngineData {
    def isEmpty = strategies.size == 0
  }


}

abstract class StrategyEngine(factory: StrategiesFactory = StrategiesFactory.empty)
                             (implicit val services: Services) {
  engine: StrategyEngine with Actor =>


  protected val strategies = mutable.Map[StrategyId, ManagedStrategy]()

  def positionUpdated(security: Security, position: Position)(implicit id: StrategyId) {
    self ! StrategyPosition(id, security, position)
  }

  def strategyLoaded(positions: Map[Security, Position])(implicit id: StrategyId) {
    self ! StrategyReady(id, positions)
  }

  def strategyStarted(implicit id: StrategyId) {
    self ! StrategyStarted(id)
  }

  def strategyStopped(positions: Map[Security, Position])(implicit id: StrategyId) {
    self ! StrategyStopped(id, positions)
  }

  protected[engine] def reconcile(portfolioPositions: immutable.Map[Security, Position],
                                  strategiesPositions: immutable.Map[(StrategyId, Security), Position]): Reconciliation = {

    // Find each side position for given security
    val groupedBySecurity = (portfolioPositions.keySet ++ strategiesPositions.keySet.map(_._2)).map(security => {
      val portfolioPos = portfolioPositions.get(security) getOrElse Position.flat
      val strategiesPos = strategiesPositions.filterKeys(_._2 == security).values.foldLeft(Position.flat)(_ |+| _)

      (security, portfolioPos, strategiesPos)
    })

    // Find mismatched position
    val mismatches = groupedBySecurity.map {
      case (security, portfolioPos, strategiesPos) if (portfolioPos == strategiesPos) => None
      case (security, portfolioPos, strategiesPos) =>
        val allocation = strategiesPositions.filterKeys(_._2 == security).toSeq.map {
          case ((id, _), position) => id -> position
        }.toMap
        Some(Mismatch(security, portfolioPos, strategiesPos, allocation))
    }

    // If found any mismatch, reconciliation failed
    val flatten = mismatches.flatten
    if (flatten.isEmpty) Reconciled else Mismatched(flatten)
  }
}

class StrategyEngineActor(factory: StrategiesFactory = StrategiesFactory.empty)
                         (implicit services: Services) extends StrategyEngine(factory)(services) with Actor with LoggingFSM[StrategyEngineState, StrategyEngineData] {

  import StrategyEngineData._
  import StrategyEngineState._

  implicit val timeout = Timeout(5.seconds)

  private val positions = mutable.Map[(StrategyId, Security), Position]()

  startWith(Idle, Void)

  when(Idle) {
    case Event(LoadStrategies, Void) =>
      log.info("Load strategies")
      factory.strategies foreach load
      goto(Loading) using PendingStrategies(strategies.keys)
  }

  when(Loading) {
    case Event(StrategyReady(id, strategyPositions), w@PendingStrategies(awaiting)) =>
      log.info("Strategy ready, Id = {}, positions = {}", id , strategyPositions)
      strategyPositions.foreach {
        case (security, position) =>
          positions(id -> security) = position
      }
      val remaining = w.copy(strategies = awaiting filterNot (_ == id))

      if (remaining.isEmpty) {
        (services(Portfolio) ? GetPositions).mapTo[Positions] map (p => reconcile(p.positions, positions.toMap)) pipeTo self
        goto(Reconciling) using Void
      }
      else stay() using remaining
  }

  when(Reconciling) {
    case Event(Reconciled, Void) =>
      goto(StrategiesReady)

    case Event(Mismatched(mismatches), Void) =>
      log.error("Reconciliation failed, mismatches = {}", mismatches)
      throw new ReconciliationFailed(mismatches)
  }

  when(StrategiesReady) {
    case Event(StartStrategies, _) =>
      log.info("Start all strategies")
      strategies.values foreach (_.ref ! Strategy.Start)
      goto(Starting) using PendingStrategies(strategies.keys)
  }

  when(Starting, stateTimeout = 10.seconds) {
    case Event(StrategyStarted(id), pending: PendingStrategies) =>
      log.info("Strategy started; Id = {}", id)
      val residual = pending.copy(strategies = pending.strategies.filterNot(_ == id))
      if (residual.isEmpty) goto(Active) else stay() using residual

    case Event(FSM.StateTimeout, PendingStrategies(pending)) =>
      log.error("Timed out starting strategies; Pending = {}", pending)
      stay()
  }

  when(Active) {
    case Event(StopStrategies, _) =>
      log.info("Stop all strategies")
      strategies.values foreach (_.ref ! Strategy.Stop)
      goto(Stopping) using PendingStrategies(strategies.keys)
  }

  when(Stopping, stateTimeout = 10.seconds) {
    case Event(StrategyStopped(id, pos), pending: PendingStrategies) =>
      log.info("Strategy stopped; Id = {}", id)
      val residual = pending.copy(strategies = pending.strategies.filterNot(_ == id))
      if (residual.isEmpty) stop(FSM.Normal) else stay() using residual

    case Event(FSM.StateTimeout, PendingStrategies(pending)) =>
      log.error("Timed out stop strategies; Pending = {}", pending)
      stop(FSM.Failure("Timed out in Stopping state"))
  }

  whenUnhandled {
    case Event(pos@StrategyPosition(id, security, position), _) =>
      positions(id -> security) = position
      stay()
  }

  initialize

  private def load(builder: StrategyBuilder) {
    log.info("Load strategy, Id = {},", builder.id)
    strategies(builder.id) = ManagedStrategy(context.actorOf(builder.props(this), builder.id.toString))
  }
}
