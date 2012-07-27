package com.ergodicity.engine.service

import com.ergodicity.engine.Engine
import com.ergodicity.engine.component.ConnectionComponent
import com.ergodicity.cgate.{Connection => ConnectionActor}
import akka.event.Logging
import akka.actor.{Terminated, ActorRef, Actor, Props}
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.core.common.WhenUnhandled

case object ConnectionService extends Service

trait Connection extends ConnectionComponent {
  engine: Engine =>

  val Connection = context.actorOf(Props(new ConnectionActor(underlyingConnection)), "Connection")
  private[this] val connectionManager = context.actorOf(Props(new ConnectionManager(engine.self, Connection)), "ConnectionManager")

  log.info("Register Connection service")
  registerService(ConnectionService, connectionManager)
}

protected[service] class ConnectionManager(engine: ActorRef, Connection: ActorRef) extends Actor with WhenUnhandled {
  val log = Logging(context.system, self)

  context.watch(Connection)
  Connection ! SubscribeTransitionCallBack(self)


  protected def receive = start orElse stop orElse trackConnectionState orElse whenUnhandled

  private def start: Receive = {
    case Service.Start => Connection !
      ConnectionActor.Open
  }

  private def stop: Receive = {
    case Service.Stop =>
      Connection ! ConnectionActor.Close
      Connection ! ConnectionActor.Dispose
  }

  private def trackConnectionState: Receive = {
    case Terminated(Connection) => engine ! ServiceFailed(ConnectionService)

    case CurrentState(Connection, com.ergodicity.cgate.Error) => engine ! ServiceFailed(ConnectionService)

    case CurrentState(Connection, com.ergodicity.cgate.Active) => engine ! ServiceActivated(ConnectionService)

    case Transition(Connection, _, com.ergodicity.cgate.Error) => engine ! ServiceFailed(ConnectionService)

    case Transition(Connection, _, com.ergodicity.cgate.Active) => engine ! ServiceActivated(ConnectionService)

    case Transition(Connection, com.ergodicity.cgate.Active, com.ergodicity.cgate.Closed) => engine ! ServicePassivated(ConnectionService)
  }
}