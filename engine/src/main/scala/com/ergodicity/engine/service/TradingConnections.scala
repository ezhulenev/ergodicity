package com.ergodicity.engine.service

import com.ergodicity.engine.{Engine, Services}
import com.ergodicity.engine.underlying.UnderlyingTradingConnections
import com.ergodicity.cgate.{Connection => CgateConnection, Active, State}
import akka.actor._
import akka.util.duration._
import ru.micexrts.cgate.{Connection => CGConnection}
import com.ergodicity.engine.service.TradingConnectionsService.{ServiceState, ServiceData}
import akka.actor.FSM.Transition
import akka.actor.FSM.CurrentState
import scala.Some
import akka.actor.Terminated
import akka.actor.FSM.SubscribeTransitionCallBack
import scalaz._
import Scalaz._

object TradingConnections {

  implicit case object TradingConnections extends ServiceId

}

trait TradingConnections {
  this: Services =>

  import TradingConnections._

  def engine: Engine with UnderlyingTradingConnections

  register(Props(new TradingConnectionsService(engine.underlyingPublisherConnection, engine.underlyingRepliesConnection)))
}

object TradingConnectionsService {

  sealed trait ServiceState

  case object Idle extends ServiceState

  case object Starting extends ServiceState

  case object Connected extends ServiceState

  case object Stopping extends ServiceState


  sealed trait ServiceData

  case object Blank extends ServiceData

  case class StartingStates(publisher: Option[State] = None, replies: Option[State] = None) extends ServiceData

  case class StoppedConnections(count: Int = 0) extends ServiceData
}

protected[service] class TradingConnectionsService(publisherConnection: CGConnection, repliesConnection: CGConnection)
                                                  (implicit val services: Services, id: ServiceId) extends Actor with LoggingFSM[ServiceState, ServiceData] with Service {

  import services._
  import TradingConnectionsService._

  val PublisherConnection = context.actorOf(Props(new CgateConnection(publisherConnection)).withDispatcher(Engine.PublisherDispatcher), "PublisherConnection")
  val RepliesConnection = context.actorOf(Props(new CgateConnection(repliesConnection)).withDispatcher(Engine.ReplyDispatcher), "RepliesConnection")

  context.watch(PublisherConnection)
  context.watch(RepliesConnection)

  startWith(Idle, Blank)

  when(Idle) {
    case Event(Service.Start, Blank) =>
      log.info("Start " + id + " service")
      // Subscribe for connection states
      PublisherConnection ! SubscribeTransitionCallBack(self)
      RepliesConnection ! SubscribeTransitionCallBack(self)

      // Open connections
      PublisherConnection ! CgateConnection.Open
      RepliesConnection ! CgateConnection.Open

      goto(Starting) using StartingStates()
  }

  when(Starting) {
    case Event(CurrentState(PublisherConnection, state: com.ergodicity.cgate.State), states@StartingStates(_, _)) =>
      startingTransition(states.copy(publisher = Some(state)))

    case Event(CurrentState(RepliesConnection, state: com.ergodicity.cgate.State), states@StartingStates(_, _)) =>
      startingTransition(states.copy(replies = Some(state)))

    case Event(Transition(PublisherConnection, _, state: com.ergodicity.cgate.State), states@StartingStates(_, _)) =>
      startingTransition(states.copy(publisher = Some(state)))

    case Event(Transition(RepliesConnection, _, state: com.ergodicity.cgate.State), states@StartingStates(_, _)) =>
      startingTransition(states.copy(replies = Some(state)))
  }

  when(Connected) {
    case Event(Service.Stop, _) =>
      log.info("Stop " + id + " service")
      PublisherConnection ! CgateConnection.Close
      PublisherConnection ! CgateConnection.Dispose
      RepliesConnection ! CgateConnection.Close
      RepliesConnection ! CgateConnection.Dispose
      goto(Stopping) using StoppedConnections()
  }

  when(Stopping, stateTimeout = 5.second) {
    case Event(Terminated(conn), StoppedConnections(cnt)) if (cnt == 0) =>
      stay() using StoppedConnections(cnt + 1)

    case Event(Terminated(conn), StoppedConnections(cnt)) if (cnt == 1) =>
      stop(FSM.Shutdown)

    case Event(FSM.StateTimeout, _) =>
      stop(FSM.Failure("Stopping timed out"))
  }

  onTermination {
    case stop => serviceStopped
  }

  whenUnhandled {
    case Event(Terminated(conn@(PublisherConnection | RepliesConnection)), _) =>
      failed("Trading connection unexpected terminated: " + conn)

    case Event(CurrentState(conn@(PublisherConnection | RepliesConnection), com.ergodicity.cgate.Error), _) =>
      failed("Trading connection switched to Error state: " + conn)

    case Event(Transition(conn@(PublisherConnection | RepliesConnection), _, com.ergodicity.cgate.Error), _) =>
      failed("Trading connection switched to Error state: " + conn)
  }

  private def startingTransition(states: StartingStates) = (states.publisher <**> states.replies)((_, _)) match {
    case Some((Active, Active)) => goto(Connected) using Blank
    case _ => stay() using states
  }

  onTransition {
    case Starting -> Connected => serviceStarted
  }
}
