package com.ergodicity.engine.service

import com.ergodicity.engine._
import akka.actor._
import akka.util.duration._
import com.ergodicity.cgate.{Connection => _, _}
import com.ergodicity.engine.Components.{CreateListener, FutInfoReplication, OptInfoReplication}
import service.Service.{Stop, Start}
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import akka.actor.FSM.Transition
import akka.actor.FSM.UnsubscribeTransitionCallBack
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.core.{SessionsTrackingState, SessionsTracking}
import underlying.UnderlyingConnection

case object InstrumentDataServiceId extends ServiceId

trait InstrumentData {
  engine: Engine with UnderlyingConnection with CreateListener with FutInfoReplication with OptInfoReplication =>

  def FutInfoStream: ActorRef

  def OptInfoStream: ActorRef

  def Sessions: ActorRef
}

trait ManagedInstrumentData extends InstrumentData {
  engine: Engine with Services with UnderlyingConnection with CreateListener with FutInfoReplication with OptInfoReplication =>

  val FutInfoStream = context.actorOf(Props(new DataStream), "FutInfoDataStream")
  val OptInfoStream = context.actorOf(Props(new DataStream), "OptInfoDataStream")

  val Sessions = context.actorOf(Props(new SessionsTracking(FutInfoStream, OptInfoStream)), "SessionsTracking")
  private[this] val sessionsManager = context.actorOf(Props(new SessionsManager(this)).withDispatcher("deque-dispatcher"), "SessionsManager")

  registerService(InstrumentDataServiceId, sessionsManager)
}

protected[service] class SessionsManager(engine: Engine with Services with UnderlyingConnection with InstrumentData with CreateListener with FutInfoReplication with OptInfoReplication) extends Actor with ActorLogging with WhenUnhandled with Stash {

  import engine._

  val ManagedSessions = Sessions

  // Listeners
  val underlyingFutInfoListener = listener(underlyingConnection, futInfoReplication(), new DataStreamSubscriber(FutInfoStream))
  val futInfoListener = context.actorOf(Props(new Listener(underlyingFutInfoListener)), "FutInfoListener")

  val underlyingOptInfoListener = listener(underlyingConnection, optInfoReplication(), new DataStreamSubscriber(OptInfoStream))
  val optInfoListener = context.actorOf(Props(new Listener(underlyingOptInfoListener)), "OptInfoListener")

  protected def receive = {
    case ServiceStarted(ConnectionServiceId) =>
      log.info("ConnectionService started, unstash all messages and start SessionsService")
      unstashAll()
      context.become {
        start orElse stop orElse handleSessionsGoesOnline orElse whenUnhandled
      }

    case msg =>
      log.info("Stash message until ConnectionService is not started = " + msg)
      stash()
  }

  private def start: Receive = {
    case Start =>
      futInfoListener ! Listener.Open(ReplicationParams(Combined))
      optInfoListener ! Listener.Open(ReplicationParams(Combined))
      ManagedSessions ! SubscribeTransitionCallBack(self)
  }

  private def handleSessionsGoesOnline: Receive = {
    case CurrentState(ManagedSessions, SessionsTrackingState.Online) =>
      ManagedSessions ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(InstrumentDataServiceId)

    case Transition(ManagedSessions, _, SessionsTrackingState.Online) =>
      ManagedSessions ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(InstrumentDataServiceId)
  }

  private def stop: Receive = {
    case Stop =>
      futInfoListener ! Listener.Close
      optInfoListener ! Listener.Close
      futInfoListener ! Listener.Dispose
      optInfoListener ! Listener.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        ServiceManager ! ServiceStopped(InstrumentDataServiceId)
        context.stop(self)
      }
  }
}