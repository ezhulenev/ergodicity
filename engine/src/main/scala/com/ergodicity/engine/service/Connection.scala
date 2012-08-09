package com.ergodicity.engine.service

import com.ergodicity.engine.{ServiceFailedException, Engine}
import com.ergodicity.cgate.{Connection => CgateConnection}
import akka.event.Logging
import akka.actor.{ActorRef, Terminated, Actor, Props}
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.util.duration._
import com.ergodicity.core.WhenUnhandled
import ru.micexrts.cgate.{Connection => CGConnection}

case object ConnectionService extends Service

trait Connection {
  engine: Engine =>

  def underlyingConnection: CGConnection

  def Connection: ActorRef
}

trait ManagedConnection extends Connection {
  engine: Engine =>

  val Connection = context.actorOf(Props(new CgateConnection(underlyingConnection)), "Connection")
  private[this] val connectionManager = context.actorOf(Props(new ConnectionManager(this)), "ConnectionManager")

  registerService(ConnectionService, connectionManager)
}

protected[service] class ConnectionManager(engine: Engine with Connection) extends Actor with WhenUnhandled {
  val log = Logging(context.system, self)

  import engine._

  val ManagedConnection = Connection

  context.watch(ManagedConnection)
  ManagedConnection ! SubscribeTransitionCallBack(self)


  protected def receive = start orElse stop orElse trackConnectionState orElse whenUnhandled

  private def start: Receive = {
    case Service.Start => ManagedConnection ! CgateConnection.Open
  }

  private def stop: Receive = {
    case Service.Stop =>
      ManagedConnection ! CgateConnection.Close
      ManagedConnection ! CgateConnection.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        ServiceManager ! ServiceStopped(ConnectionService)
        context.stop(self)
      }
  }

  private def trackConnectionState: Receive = {
    case Terminated(ManagedConnection) => throw new ServiceFailedException(ConnectionService, "Connection unexpected terminated")

    case CurrentState(ManagedConnection, com.ergodicity.cgate.Error) => throw new ServiceFailedException(ConnectionService, "Connection switched to Error state")

    case Transition(ManagedConnection, _, com.ergodicity.cgate.Error) => throw new ServiceFailedException(ConnectionService, "Connection switched to Error state")

    case CurrentState(ManagedConnection, com.ergodicity.cgate.Active) => ServiceManager ! ServiceStarted(ConnectionService)

    case Transition(ManagedConnection, _, com.ergodicity.cgate.Active) => ServiceManager ! ServiceStarted(ConnectionService)
  }
}