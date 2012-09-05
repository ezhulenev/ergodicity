package com.ergodicity.engine.service

import com.ergodicity.engine.underlying.{ListenerFactory, UnderlyingConnection, UnderlyingListener}
import com.ergodicity.engine.{Services, Engine}
import com.ergodicity.engine.ReplicationScheme.PosReplication
import akka.actor.{ActorRef, Actor, ActorLogging, Props}
import akka.util.duration._
import ru.micexrts.cgate.{Connection => CGConnection}
import com.ergodicity.cgate.config.Replication
import com.ergodicity.cgate.{Listener, DataStreamSubscriber, DataStream, WhenUnhandled}
import com.ergodicity.core.{Isin, PositionsTrackingState, PositionsTracking}
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import com.ergodicity.core.position.Position
import com.ergodicity.engine.strategy.StrategyId
import collection.mutable
import com.ergodicity.core.PositionsTracking._
import akka.actor.FSM.Transition
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.core.PositionsTracking.GetPositionActor
import com.ergodicity.core.PositionsTracking.NewPosition
import akka.actor.FSM.CurrentState
import akka.actor.FSM.UnsubscribeTransitionCallBack
import com.ergodicity.core.PositionsTracking.OpenPositions
import com.ergodicity.core.PositionsTracking.SubscribeOpenPositions
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.core.position.PositionActor.{PositionTransition, CurrentPosition, SubscribePositionUpdates}

object Portfolio {

  implicit case object Portfolio extends ServiceId

}

trait Portfolio {
  this: Services =>

  import Portfolio._

  def engine: Engine with UnderlyingConnection with UnderlyingListener with PosReplication

  register(Props(new PortfolioService(engine.listenerFactory, engine.underlyingConnection, engine.posReplication)))
}

protected[service] class PortfolioService(listener: ListenerFactory, underlyingConnection: CGConnection, posReplication: Replication)
                                         (implicit val services: Services, id: ServiceId) extends Actor with ActorLogging with WhenUnhandled {

  import services._

  val PosStream = context.actorOf(Props(new DataStream), "PosDataStream")

  val Positions = context.actorOf(Props(new PositionsTracking(PosStream)), "Positions")

  val underlyingPosListener = listener(underlyingConnection, posReplication(), new DataStreamSubscriber(PosStream))
  val posListener = context.actorOf(Props(new Listener(underlyingPosListener)), "PosListener")

  protected def receive = start orElse stop orElse handlePositionsGoesOnline orElse whenUnhandled

  private def start: Receive = {
    case Start =>
      log.info("Start " + id + " service")
      posListener ! Listener.Open(ReplicationParams(Combined))
      Positions ! SubscribeTransitionCallBack(self)
  }

  private def handlePositionsGoesOnline: Receive = {
    case CurrentState(Positions, PositionsTrackingState.Online) =>
      Positions ! UnsubscribeTransitionCallBack(self)
      serviceStarted

    case Transition(Positions, _, PositionsTrackingState.Online) =>
      Positions ! UnsubscribeTransitionCallBack(self)
      serviceStarted
  }

  private def stop: Receive = {
    case Stop =>
      log.info("Stop " + id + " service")
      posListener ! Listener.Close
      posListener ! Listener.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        serviceStopped
        context.stop(self)
      }
  }
}

object PortfolioReconciliation {

  sealed trait Reconciliation

  case object Reconciled extends Reconciliation

  case object Mismatched extends Reconciliation

  case class Mismatch(isin: Isin, portfolioPosition: Position, strategiesPositions: Position, strategiesAllocation: Map[StrategyId, Position])
}

class PortfolioReconciliation(portfolio: ActorRef) extends Actor with ActorLogging with WhenUnhandled {

  val portfolioPositions = mutable.Map[Isin, Position]()

  override def preStart() {
    log.info("Start portfolione reconcillation")
    portfolio ! SubscribeOpenPositions(self)
  }

  protected def receive = handlePortfolioMessages orElse whenUnhandled

  private def handlePortfolioMessages: Receive = {
    case OpenPositions(positions) =>
      positions foreach (portfolio ! GetPositionActor(_))

    case NewPosition(isin) =>
      portfolio ! GetPositionActor(isin)

    case TrackedPosition(isin, positionActor) =>
      log.debug("Subscribe for position updates = " + isin + ", ref = " + positionActor)
      positionActor ! SubscribePositionUpdates(self)

    case CurrentPosition(isin, position) =>
      portfolioPositions(isin) = position

    case PositionTransition(isin, _, position) =>
      portfolioPositions(isin) = position
  }
}