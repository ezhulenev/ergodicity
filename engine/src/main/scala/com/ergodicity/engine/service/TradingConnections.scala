package com.ergodicity.engine.service

import com.ergodicity.engine.{ServiceFailedException, Engine}
import akka.actor._
import akka.util.duration._
import com.ergodicity.cgate.{Connection => ErgodicityConnection, Active, State}
import ru.micexrts.cgate.{Connection => CGConnection}
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.engine.service.TradingConnectionsManager.{ManagerData, ManagerState}
import scalaz._
import Scalaz._

case object TradingConnectionsServiceId extends ServiceId

trait TradingConnections {
  this: Engine =>

  def underlyingPublisherConnection: CGConnection

  def underlyingRepliesConnection: CGConnection

  def PublisherConnection: ActorRef

  def RepliesConnection: ActorRef
}


trait ManagedTradingConnections extends TradingConnections {
  engine: Engine =>

  val PublisherConnection = context.actorOf(Props(new ErgodicityConnection(underlyingPublisherConnection)), "PublisherConnection")
  val RepliesConnection = context.actorOf(Props(new ErgodicityConnection(underlyingRepliesConnection)), "RepliesConnection")

  private[this] val connectionManager = context.actorOf(Props(new TradingConnectionsManager(this)), "TradingConnectionsManager")

  registerService(TradingConnectionsServiceId, connectionManager)
}


object TradingConnectionsManager {

  sealed trait ManagerState

  case object Idle extends ManagerState

  case object Starting extends ManagerState

  case object Connected extends ManagerState

  case object Stopping extends ManagerState


  sealed trait ManagerData

  case object Blank extends ManagerData

  case class ConnectionsStates(publisher: Option[State] = None, replies: Option[State] = None) extends ManagerData

}

protected[service] class TradingConnectionsManager(engine: Engine with TradingConnections) extends Actor with FSM[ManagerState, ManagerData] {

  import engine._
  import TradingConnectionsManager._

  val ManagedPublisherConnection = PublisherConnection
  val ManagedRepliesConnection = RepliesConnection

  context.watch(ManagedPublisherConnection)
  context.watch(ManagedRepliesConnection)

  startWith(Idle, Blank)

  when(Idle) {
    case Event(Service.Start, Blank) =>
      // Subscribe for connection states
      ManagedPublisherConnection ! SubscribeTransitionCallBack(self)
      ManagedRepliesConnection ! SubscribeTransitionCallBack(self)

      // Open connections
      ManagedPublisherConnection ! ErgodicityConnection.Open
      ManagedRepliesConnection ! ErgodicityConnection.Open

      goto(Starting) using ConnectionsStates()
  }

  when(Starting) {
    case Event(CurrentState(ManagedPublisherConnection, state: com.ergodicity.cgate.State), states@ConnectionsStates(_, _)) =>
      handleConnectionsStates(states.copy(publisher = Some(state)))

    case Event(CurrentState(ManagedRepliesConnection, state: com.ergodicity.cgate.State), states@ConnectionsStates(_, _)) =>
      handleConnectionsStates(states.copy(replies = Some(state)))

    case Event(Transition(ManagedPublisherConnection, _, state: com.ergodicity.cgate.State), states@ConnectionsStates(_, _)) =>
      handleConnectionsStates(states.copy(publisher = Some(state)))

    case Event(Transition(ManagedRepliesConnection, _, state: com.ergodicity.cgate.State), states@ConnectionsStates(_, _)) =>
      handleConnectionsStates(states.copy(replies = Some(state)))
  }

  when(Connected) {
    case Event(Service.Stop, _) =>
      ManagedPublisherConnection ! ErgodicityConnection.Close
      ManagedRepliesConnection ! ErgodicityConnection.Close
      ManagedPublisherConnection ! ErgodicityConnection.Close
      ManagedRepliesConnection ! ErgodicityConnection.Dispose
      goto(Stopping) forMax (1.second)
  }

  when(Stopping) {
    case Event(FSM.StateTimeout, _) =>
      ServiceManager ! ServiceStopped(TradingConnectionsServiceId)
      stop(FSM.Shutdown)
  }

  whenUnhandled {
    case Event(Terminated(ManagedPublisherConnection | ManagedRepliesConnection), _) =>
      throw new ServiceFailedException(ConnectionServiceId, "Trading connection unexpected terminated")

    case Event(CurrentState(ManagedPublisherConnection | ManagedRepliesConnection, com.ergodicity.cgate.Error), _) =>
      throw new ServiceFailedException(ConnectionServiceId, "Trading connection switched to Error state")

    case Event(Transition(ManagedPublisherConnection | ManagedRepliesConnection, _, com.ergodicity.cgate.Error), _) =>
      throw new ServiceFailedException(ConnectionServiceId, "Trading connection switched to Error state")
  }

  private def handleConnectionsStates(states: ConnectionsStates) = (states.publisher <**> states.replies)((_, _)) match {
    case Some((Active, Active)) => goto(Connected) using Blank
    case _ => stay() using states
  }

  onTransition {
    case Starting -> Connected => ServiceManager ! ServiceStarted(TradingConnectionsServiceId)
  }
}