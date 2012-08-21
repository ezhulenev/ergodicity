package com.ergodicity.engine.service

import com.ergodicity.engine._
import akka.actor._
import akka.util.duration._
import com.ergodicity.cgate.WhenUnhandled
import com.ergodicity.core.{Sessions => CoreSessions, SessionsState}
import com.ergodicity.cgate.{Listener, DataStreamSubscriber, DataStream}
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
  val OptInfoStream = context.actorOf(Props(new DataStream), "OptInfoDataStream")

  val Sessions = context.actorOf(Props(new CoreSessions(FutInfoStream, OptInfoStream)), "Sessions")
  private[this] val sessionsManager = context.actorOf(Props(new SessionsManager(this)).withDispatcher("deque-dispatcher"), "SessionsManager")

  registerService(SessionsService, sessionsManager)
}

protected[service] class SessionsManager(engine: Engine with Connection with Sessions with CreateListener with FutInfoReplication with OptInfoReplication) extends Actor with ActorLogging with WhenUnhandled with Stash {
  import engine._

  val ManagedSessions = Sessions

  // Listeners
  val underlyingFutInfoListener = listener(underlyingConnection, futInfoReplication(), new DataStreamSubscriber(FutInfoStream))
  val futInfoListener = context.actorOf(Props(new Listener(underlyingFutInfoListener)), "FutInfoListener")

  val underlyingOptInfoListener = listener(underlyingConnection, optInfoReplication(), new DataStreamSubscriber(OptInfoStream))
  val optInfoListener = context.actorOf(Props(new Listener(underlyingOptInfoListener)), "OptInfoListener")

  protected def receive = {
    case ServiceStarted(ConnectionService) =>
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
      context.system.scheduler.scheduleOnce(1.second) {
        ServiceManager ! ServiceStopped(SessionsService)
        context.stop(self)
      }
  }
}
