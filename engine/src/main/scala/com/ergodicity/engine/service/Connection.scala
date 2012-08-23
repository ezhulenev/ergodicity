package com.ergodicity.engine.service

import com.ergodicity.engine.{Services, ServiceFailedException, Engine}
import com.ergodicity.cgate.{Connection => CgateConnection, WhenUnhandled}
import akka.actor._
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.util.duration._
import com.ergodicity.engine.underlying.UnderlyingConnection

case object ConnectionServiceId extends ServiceId

trait Connection {
  engine: Engine with UnderlyingConnection with Services =>

  private[this] val connectionManager = context.actorOf(Props(new ConnectionManager(this)), "ConnectionManager")

  registerService(ConnectionServiceId, connectionManager)
}

protected[service] class ConnectionManager(engine: Engine with UnderlyingConnection with Services) extends Actor with ActorLogging with WhenUnhandled {

  import engine._

  val Connection = context.actorOf(Props(new CgateConnection(underlyingConnection)), "Connection")

  context.watch(Connection)
  Connection ! SubscribeTransitionCallBack(self)

  protected def receive = start orElse stop orElse trackConnectionState orElse whenUnhandled

  private def start: Receive = {
    case Service.Start => Connection ! CgateConnection.Open
  }

  private def stop: Receive = {
    case Service.Stop =>
      Connection ! CgateConnection.Close
      Connection ! CgateConnection.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        ServiceManager ! ServiceStopped(ConnectionServiceId)
        context.stop(self)
      }
      context.become(whenUnhandled)
  }

  private def trackConnectionState: Receive = {
    case Terminated(Connection) => throw new ServiceFailedException(ConnectionServiceId, "Connection unexpected terminated")

    case CurrentState(Connection, com.ergodicity.cgate.Error) => throw new ServiceFailedException(ConnectionServiceId, "Connection switched to Error state")

    case Transition(Connection, _, com.ergodicity.cgate.Error) => throw new ServiceFailedException(ConnectionServiceId, "Connection switched to Error state")

    case CurrentState(Connection, com.ergodicity.cgate.Active) => ServiceManager ! ServiceStarted(ConnectionServiceId)

    case Transition(Connection, _, com.ergodicity.cgate.Active) => ServiceManager ! ServiceStarted(ConnectionServiceId)
  }
}