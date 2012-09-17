package com.ergodicity.engine

import service.Portfolio.Portfolio
import strategy.Strategy.Start
import strategy.{StrategyBuilder, StrategiesFactory, StrategyId}
import akka.actor.{LoggingFSM, Actor, ActorRef}
import com.ergodicity.engine.StrategyEngine._
import collection.mutable
import collection.immutable
import com.ergodicity.core.position.Position
import com.ergodicity.core.Security
import com.ergodicity.engine.StrategyEngine.ManagedStrategy
import akka.util.Timeout
import akka.util.duration._
import akka.pattern.ask
import akka.pattern.pipe
import scalaz._
import Scalaz._
import com.ergodicity.core.PositionsTracking.{Positions, GetPositions}

object StrategyEngine {

  case class ManagedStrategy(ref: ActorRef)

  // Engine messages
  case object PrepareStrategies

  case object StartStrategies

  case object StopStrategies

  // Messages from strategies
  sealed trait StrategyNotification {
    def id: StrategyId
  }

  case class StrategyPosition(id: StrategyId, security: Security, position: Position) extends StrategyNotification

  case class StrategyReady(id: StrategyId, positions: Map[Security, Position]) extends StrategyNotification

  // Positions Reconciliation
  sealed trait Reconciliation

  case object Reconciled extends Reconciliation

  case class Mismatched(mismatches: Iterable[Mismatch]) extends Reconciliation

  case class Mismatch(security: Security, portfolioPosition: Position, strategiesPosition: Position, strategiesAllocation: Map[StrategyId, Position])

  class ReconciliationFailed(mismatches: Iterable[Mismatch]) extends RuntimeException("Reconciliation failed; Mismatches size = " + mismatches.size)

}

sealed trait StrategyEngineState

object StrategyEngineState {

  case object Idle extends StrategyEngineState

  case object Preparing extends StrategyEngineState

  case object Reconciling extends StrategyEngineState

  case object StrategiesReady extends StrategyEngineState

}

sealed trait StrategyEngineData

object StrategyEngineData {

  case object Void extends StrategyEngineData

  case class AwaitingReadiness(strategies: Iterable[StrategyId]) extends StrategyEngineData {
    def isEmpty = strategies.size == 0
  }


}

abstract class StrategyEngine(factory: StrategiesFactory = StrategiesFactory.empty)
                             (implicit val services: Services) {
  engine: StrategyEngine with Actor =>


  protected val strategies = mutable.Map[StrategyId, ManagedStrategy]()

  def reportPosition(security: Security, position: Position)(implicit id: StrategyId) {
    self ! StrategyPosition(id, security, position)
  }

  def reportReady(positions: Map[Security, Position])(implicit id: StrategyId) {
    self ! StrategyReady(id, positions)
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

    // If found any mismath, reconcilation failed
    val flatten = mismatches.flatten
    if (flatten.isEmpty) Reconciled else Mismatched(flatten)
  }
}

class StrategyEngineActor(factory: StrategiesFactory = StrategiesFactory.empty)
                         (implicit services: Services) extends StrategyEngine(factory)(services) with Actor with LoggingFSM[StrategyEngineState, StrategyEngineData] {

  import StrategyEngineState._
  import StrategyEngineData._

  implicit val timeout = Timeout(5.seconds)

  private val positions = mutable.Map[(StrategyId, Security), Position]()

  startWith(Idle, Void)

  when(Idle) {
    case Event(PrepareStrategies, Void) =>
      log.info("Preparing strategies = " + strategies.keys)
      factory.strategies foreach start
      goto(Preparing) using AwaitingReadiness(strategies.keys)
  }

  when(Preparing) {
    case Event(StrategyReady(id, strategyPositions), w@AwaitingReadiness(awaiting)) =>
      log.info("Strategy ready, Id = " + id + ", positions = " + strategyPositions)
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
      log.error("Reconciliation failed, mismatches = "+mismatches)
      throw new ReconciliationFailed(mismatches)
  }

  when(StrategiesReady) {
    case Event(StartStrategies, _) =>
      log.info("Start all strategies")
      strategies.values foreach (_.ref ! Start)
      stay()
  }

  whenUnhandled {
    case Event(pos@StrategyPosition(id, security, position), _) =>
      positions(id -> security) = position
      stay()
  }

  initialize

  private def start(builder: StrategyBuilder) {
    log.info("Start strategy, Id = " + builder.id)
    strategies(builder.id) = ManagedStrategy(context.actorOf(builder.props(this), builder.id.toString))
  }
}
