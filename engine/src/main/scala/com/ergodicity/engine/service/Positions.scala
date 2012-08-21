package com.ergodicity.engine.service

import com.ergodicity.engine.Components.{CreateListener, PosReplication}
import com.ergodicity.engine.Engine
import com.ergodicity.core.position.{Positions => PositionsCore, PositionsState}
import akka.actor._
import akka.util.duration._
import com.ergodicity.cgate.{WhenUnhandled, Listener, DataStreamSubscriber, DataStream}
import akka.event.Logging
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import akka.actor.FSM.{Transition, UnsubscribeTransitionCallBack, CurrentState, SubscribeTransitionCallBack}

case object PositionsService extends Service

trait Positions {
  engine: Engine =>

  def PosStream: ActorRef

  def Positions: ActorRef
}

trait ManagedPositions extends Positions {
  engine: Engine with Connection with CreateListener with PosReplication =>

  val PosStream = context.actorOf(Props(new DataStream), "PosDataStream")

  val Positions = context.actorOf(Props(new PositionsCore(PosStream)), "Positions")
  private[this] val positionsManager = context.actorOf(Props(new PositionsManager(this)).withDispatcher("deque-dispatcher"), "PositionsManager")

  registerService(PositionsService, positionsManager)
}

protected[service] class PositionsManager(engine: Engine with Connection with Positions with CreateListener with PosReplication) extends Actor with ActorLogging with WhenUnhandled with Stash {
  import engine._

  val ManagedPositions = Positions

  val underlyingPosListener = listener(underlyingConnection, posReplication(), new DataStreamSubscriber(PosStream))
  val posListener = context.actorOf(Props(new Listener(underlyingPosListener)), "PosListener")

  protected def receive = {
    case ServiceStarted(ConnectionService) =>
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
    case CurrentState(ManagedPositions, PositionsState.Online) =>
      ManagedPositions ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(PositionsService)

    case Transition(ManagedPositions, _, PositionsState.Online) =>
      ManagedPositions ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(PositionsService)
  }

  private def stop: Receive = {
    case Stop =>
      posListener ! Listener.Close
      posListener ! Listener.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        ServiceManager ! ServiceStopped(PositionsService)
        context.stop(self)
      }
  }
}
