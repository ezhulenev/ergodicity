package com.ergodicity.engine.service

import com.ergodicity.engine.{Services, Engine}
import com.ergodicity.cgate.{Connection => CgateConnection, WhenUnhandled}
import akka.actor._
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.util.duration._
import com.ergodicity.engine.underlying.UnderlyingConnection
import ru.micexrts.cgate.{Connection => CGConnection}

trait Connection {
  this: Services =>

  implicit case object Id extends ServiceId

  def engine: Engine with UnderlyingConnection

  register(context.actorOf(Props(new ConnectionManager(engine.underlyingConnection)), "ConnectionManager"))
}

protected[service] class ConnectionManager(underlyingConnection: CGConnection)(implicit val services: Services, id: ServiceId) extends Actor with ActorLogging with WhenUnhandled {
  import services._

  val Connection = context.actorOf(Props(new CgateConnection(underlyingConnection)), "Connection")

  context.watch(Connection)
  Connection ! SubscribeTransitionCallBack(self)

  protected def receive = start orElse stop orElse trackConnectionState orElse whenUnhandled

  private def start: Receive = {
    case Service.Start =>
      Connection ! CgateConnection.Open
  }

  private def stop: Receive = {
    case Service.Stop =>
      Connection ! CgateConnection.Close
      Connection ! CgateConnection.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        serviceStopped
        context.stop(self)
      }
      context.become(whenUnhandled)
  }

  private def trackConnectionState: Receive = {
    case Terminated(Connection) => serviceFailed("Connection unexpected terminated")

    case CurrentState(Connection, com.ergodicity.cgate.Error) => serviceFailed("Connection switched to Error state")

    case Transition(Connection, _, com.ergodicity.cgate.Error) => serviceFailed("Connection switched to Error state")

    case CurrentState(Connection, com.ergodicity.cgate.Active) => serviceStarted

    case Transition(Connection, _, com.ergodicity.cgate.Active) => serviceStarted
  }
}