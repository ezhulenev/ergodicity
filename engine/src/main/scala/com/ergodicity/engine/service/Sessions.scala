package com.ergodicity.engine.service

import com.ergodicity.engine._
import akka.actor.{Stash, ActorRef, Actor, Props}
import com.ergodicity.core.{Sessions => CoreSessions, SessionsState, WhenUnhandled}
import com.ergodicity.cgate.{BindListener, Listener, DataStreamSubscriber, DataStream}
import akka.event.Logging
import com.ergodicity.engine.Components.{CreateListener, FutInfoReplication, OptInfoReplication}
import service.Service.{Stop, Start}
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import akka.actor.FSM.{UnsubscribeTransitionCallBack, Transition, CurrentState, SubscribeTransitionCallBack}


case object SessionsService extends Service

trait Sessions {
  engine: Engine with Connection with CreateListener with FutInfoReplication with OptInfoReplication =>

  def FutInfoStream: ActorRef

  def OptInfoStream: ActorRef

  def Sessions: ActorRef
}

trait ManagedSessions extends Sessions {
  engine: Engine with Connection with CreateListener with FutInfoReplication with OptInfoReplication =>

  val FutInfoStream = context.actorOf(Props(new DataStream), "FutInfoDataStream")
  val OptInfoStream = context.actorOf(Props(new DataStream), "FutInfoDataStream")

  val Sessions = context.actorOf(Props(new CoreSessions(FutInfoStream, OptInfoStream)).withDispatcher("deque-dispatcher"), "Sessions")
  private[this] val sessionsManager = context.actorOf(Props(new SessionsManager(this)), "SessionsManager")

  log.info("Register Sessions service")
  registerService(SessionsService, sessionsManager)
}

protected[service] class SessionsManager(engine: Engine with Connection with Sessions with CreateListener with FutInfoReplication with OptInfoReplication) extends Actor with WhenUnhandled with Stash {
  val log = Logging(context.system, self)

  import engine._

  val ManagedSessions = Sessions

  // Listeners
  val underlyingFutInfoListener = listener(underlyingConnection, futInfoReplication(), new DataStreamSubscriber(FutInfoStream))
  val futInfoListener = context.actorOf(Props(new Listener(BindListener(underlyingFutInfoListener) to Connection)), "FutInfoListener")

  val underlyingOptInfoListener = listener(underlyingConnection, optInfoReplication(), new DataStreamSubscriber(FutInfoStream))
  val optInfoListener = context.actorOf(Props(new Listener(BindListener(underlyingOptInfoListener) to Connection)), "OptInfoListener")

  protected def receive = {
    case ServiceStarted(ConnectionService) =>
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
    case CurrentState(ManagedSessions, SessionsState.Online) =>
      ManagedSessions ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(SessionsService)

    case Transition(ManagedSessions, _, SessionsState.Online) =>
      ManagedSessions ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(SessionsService)
  }

  private def stop: Receive = {
    case Stop =>
      futInfoListener ! Listener.Close
      optInfoListener ! Listener.Close
      futInfoListener ! Listener.Dispose
      optInfoListener ! Listener.Dispose
      ServiceManager ! ServiceStopped(SessionsService)
  }
}
