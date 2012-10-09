package com.ergodicity.engine.service

import com.ergodicity.engine.{Services, Engine}
import com.ergodicity.cgate.{Connection => CgateConnection, WhenUnhandled}
import akka.actor._
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.util.duration._
import com.ergodicity.engine.underlying.{UnderlyingTradingConnections, UnderlyingConnection}
import ru.micexrts.cgate.{Connection => CGConnection, CGateException}
import com.ergodicity.cgate.Connection.StartMessageProcessing
import akka.util.Duration

object ReplicationConnection {

  implicit case object Connection extends ServiceId

}

trait ReplicationConnection {
  this: Services =>

  import ReplicationConnection._

  private[this] implicit val config = ConnectionConfig(Engine.ReplicationDispatcher)

  def engine: Engine with UnderlyingConnection

  register(Props(new ConnectionService(engine.underlyingConnection)))
}


object TradingConnection {

  implicit case object TradingConnection extends ServiceId

}

trait TradingConnection {
  this: Services =>

  import TradingConnection._

  private[this] implicit val config = ConnectionConfig(Engine.ReplicationDispatcher, processingDuration = 10.millis)

  def engine: Engine with UnderlyingTradingConnections

  register(Props(new ConnectionService(engine.underlyingTradingConnection)))
}

case class ConnectionConfig(dispatcher: String, processingDuration: Duration = 10.millis)

protected[service] class ConnectionService(underlyingConnection: CGConnection)
                                          (implicit val services: Services, id: ServiceId, config: ConnectionConfig) extends Actor with ActorLogging with WhenUnhandled with Service {

  import services._

  val Connection = context.actorOf(Props(new CgateConnection(underlyingConnection)).withDispatcher(config.dispatcher), "Connection")

  // Stop Connection on any CGException
  override def supervisorStrategy() = AllForOneStrategy() {
    case _: CGateException => SupervisorStrategy.Stop
  }

  // Watch for Connection state and liveness
  context.watch(Connection)
  Connection ! SubscribeTransitionCallBack(self)

  protected def receive = start orElse stop orElse trackConnectionState orElse whenUnhandled

  private def start: Receive = {
    case Service.Start =>
      log.info("Start " + id + " service")
      Connection ! CgateConnection.Open
  }

  private def stop: Receive = {
    case Service.Stop =>
      log.info("Stop " + id + " service")
      Connection ! CgateConnection.Close
      Connection ! CgateConnection.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        serviceStopped
        context.stop(self)
      }
      context.become(whenUnhandled)
  }

  private def trackConnectionState: Receive = {
    case Terminated(Connection) =>
      failed("Connection unexpected terminated")

    case CurrentState(Connection, com.ergodicity.cgate.Error) =>
      failed("Connection switched to Error state")

    case Transition(Connection, _, com.ergodicity.cgate.Error) =>
      failed("Connection switched to Error state")

    case CurrentState(Connection, com.ergodicity.cgate.Active) =>
      Connection ! StartMessageProcessing(config.processingDuration)
      serviceStarted

    case Transition(Connection, _, com.ergodicity.cgate.Active) =>
      Connection ! StartMessageProcessing(config.processingDuration)
      serviceStarted
  }
}