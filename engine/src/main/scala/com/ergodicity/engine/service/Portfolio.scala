package com.ergodicity.engine.service

import com.ergodicity.engine.Components.{CreateListener, PosReplication}
import com.ergodicity.engine.{Services, Engine}
import akka.actor._
import akka.util.duration._
import com.ergodicity.cgate.{Connection => _, _}
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import akka.actor.FSM.Transition
import config.Replication.ReplicationParams
import akka.actor.FSM.CurrentState
import akka.actor.FSM.UnsubscribeTransitionCallBack
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.core.{PositionsTrackingState, PositionsTracking}
import com.ergodicity.engine.underlying.UnderlyingConnection

case object PortfolioServiceId extends ServiceId

trait Portfolio {
  engine: Engine =>

  def PosStream: ActorRef

  def Positions: ActorRef
}

trait ManagedPortfolio extends Portfolio {
  engine: Engine with Services with UnderlyingConnection with CreateListener with PosReplication =>

  val PosStream = context.actorOf(Props(new DataStream), "PosDataStream")

  val Positions = context.actorOf(Props(new PositionsTracking(PosStream)), "Portfolio")
  private[this] val positionsManager = context.actorOf(Props(new PositionsManager(this)).withDispatcher("deque-dispatcher"), "PositionsManager")

  registerService(PortfolioServiceId, positionsManager)
}

protected[service] class PositionsManager(engine: Engine with Services with UnderlyingConnection with Portfolio with CreateListener with PosReplication) extends Actor with ActorLogging with WhenUnhandled with Stash {

  import engine._

  val ManagedPositions = Positions

  val underlyingPosListener = listener(underlyingConnection, posReplication(), new DataStreamSubscriber(PosStream))
  val posListener = context.actorOf(Props(new Listener(underlyingPosListener)), "PosListener")

  protected def receive = {
    case ServiceStarted(ConnectionServiceId) =>
      log.info("ConnectionService started, unstash all messages and start PositionsService")
      unstashAll()
      context.become {
        start orElse stop orElse handlePositionsGoesOnline orElse whenUnhandled
      }

    case msg =>
      log.info("Stash message until ConnectionService is not started = " + msg)
      stash()
  }

  private def start: Receive = {
    case Start =>
      posListener ! Listener.Open(ReplicationParams(Combined))
      ManagedPositions ! SubscribeTransitionCallBack(self)
  }

  private def handlePositionsGoesOnline: Receive = {
    case CurrentState(ManagedPositions, PositionsTrackingState.Online) =>
      ManagedPositions ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(PortfolioServiceId)

    case Transition(ManagedPositions, _, PositionsTrackingState.Online) =>
      ManagedPositions ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(PortfolioServiceId)
  }

  private def stop: Receive = {
    case Stop =>
      posListener ! Listener.Close
      posListener ! Listener.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        ServiceManager ! ServiceStopped(PortfolioServiceId)
        context.stop(self)
      }
  }
}