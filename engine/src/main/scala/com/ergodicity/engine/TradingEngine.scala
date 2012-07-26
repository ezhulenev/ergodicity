package com.ergodicity.engine

import com.jacob.com.ComFailException
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.util.duration._
import com.ergodicity.core.{SessionsState, Sessions}
import com.ergodicity.core.position.{Positions, PositionsState}
import com.ergodicity.core.position.Positions.BindPositions
import component._
import akka.actor.FSM.{UnsubscribeTransitionCallBack, Transition, SubscribeTransitionCallBack, Failure => FSMFailure}
import akka.util.Timeout
import com.ergodicity.core.Sessions.{CurrentOngoingSession, OngoingSessionTransition, SubscribeOngoingSessions, BindSessions}
import com.ergodicity.cgate._
import config.Replication.ReplicationMode.Combined
import config.Replication.ReplicationParams
import com.ergodicity.cgate.Connection.StartMessageProcessing

object TradingEngine {
  implicit val timeout = Timeout(5 seconds)
  val OngoingSessionRepeat = 5
}

sealed trait TradingEngineState

object TradingEngineState {

  case object Idle extends TradingEngineState

  case object Connecting extends TradingEngineState

  case object Initializing extends TradingEngineState

  case object WaitingOngoingSession extends TradingEngineState

  case object Trading extends TradingEngineState

}

sealed trait TradingEngineData

object TradingEngineData {

  case object Blank extends TradingEngineData

  case class InitializationState(sessions: Option[SessionsState], positions: Option[PositionsState]) extends TradingEngineData

  case class OngoingSession(session: ActorRef) extends TradingEngineData

}

case object StartTradingEngine

case object ShutDownTradingEngine

class TradingEngine(processMessagesTimeout: Int) extends Actor with FSM[TradingEngineState, TradingEngineData] {
  this: Actor with FSM[TradingEngineState, TradingEngineData] with ConnectionComponent
    with FutInfoListenerComponent with OptInfoListenerComponent with PosListenerComponent =>

  import TradingEngineState._
  import TradingEngineData._

  log.info("Create TradingEngine; Connection = " + underlyingConnection)

  // Supervisor
  override val supervisorStrategy = AllForOneStrategy() {
    case e: ComFailException =>
      log.error("Got ComFailException: " + e + ", stop Trading Engine")
      Stop
  }

  // Create connection
  val Connection = context.actorOf(Props(new Connection(underlyingConnection)), "Connection")
  context.watch(Connection)
  Connection ! SubscribeTransitionCallBack(self)

  // Create DataStreams
  val FutInfoStream = context.actorOf(Props(new DataStream), "FuturesInfoDataStream")
  val OptInfoStream = context.actorOf(Props(new DataStream), "OptionsInfoDataStream")
  val PosStream = context.actorOf(Props(new DataStream), "PositionsDataStream")

  // Create Listeners
  val FutInfoListener = context.actorOf(Props(new Listener(underlyingFutInfoListener(new DataStreamSubscriber(FutInfoStream)))), "FutInfoListener")
  val OptInfoListener = context.actorOf(Props(new Listener(underlyingOptInfoListener(new DataStreamSubscriber(OptInfoStream)))), "OptInfoListener")
  val PosListener = context.actorOf(Props(new Listener(underlyingPosListener(new DataStreamSubscriber(PosStream)))), "PosListener")

  // Sessions tracking
  val Sessions = context.actorOf(Props(new Sessions(FutInfoStream, OptInfoStream)), "Sessions")

  // Positions tracking
  val Positions = context.actorOf(Props(new Positions(PosStream)), "Positions")

  startWith(Idle, Blank)

  when(Idle) {
    case Event(StartTradingEngine, _) =>
      log.info("Start trading engine; Open connection")
      Connection ! com.ergodicity.cgate.Connection.Open
      goto(Connecting)
  }

  when(Connecting) {
    case Event(Transition(Connection, _, Active), _) => goto(Initializing) using InitializationState(None, None)
    case Event(Terminated(Connection), _) => stop(FSMFailure("Connection terminated"))
  }

  when(Initializing) {
    case Event(Transition(Sessions, _, state: SessionsState), initializing: InitializationState) =>
      handleInitializationState(initializing.copy(sessions = Some(state)))

    case Event(Transition(Positions, _, state: PositionsState), initializing: InitializationState) =>
      handleInitializationState(initializing.copy(positions = Some(state)))
  }

  when(WaitingOngoingSession, stateTimeout = 5.second) {
    case Event(CurrentOngoingSession(Some(session)), _) =>
      log.info("Ongoins session set to: " + session)
      goto(Trading) using OngoingSession(session)

    case Event(OngoingSessionTransition(Some(session)), _) =>
      log.info("Ongoins session set to: " + session)
      goto(Trading) using OngoingSession(session)

    case Event(FSM.StateTimeout, _) => stop(FSMFailure("Failed connect to ongoing session"))
  }

  when(Trading) {
    case Event("NoSuchEventBlyat", _) => stay()
  }

  onTransition {
    case Idle -> Connecting =>
      log.debug("Establishing connection")

    case Connecting -> Initializing =>
      log.debug("Initializing Trading Engine")

      // Bind sessions tracker to data streams
      Sessions ! SubscribeTransitionCallBack(self)
      Sessions ! BindSessions

      // Bind positions
      Positions ! SubscribeTransitionCallBack(self)
      Positions ! BindPositions

    case Initializing -> WaitingOngoingSession =>
      log.debug("Initialization finished, subscribe for ongoing sessions!")
      Sessions ! UnsubscribeTransitionCallBack(self)
      Positions ! UnsubscribeTransitionCallBack(self)
      Sessions ! SubscribeOngoingSessions(self)

    case _ -> Trading =>
      log.debug("Joined session, start trading")
  }

  private def handleInitializationState(initialization: InitializationState): State = {
    import scalaz._
    import Scalaz._

    (initialization.sessions |@| initialization.positions) {
      (_, _)
    } match {
      case Some((SessionsState.Binded, PositionsState.Binded)) =>
        log.debug("Open Sessions & Positions underlying streams")
        // Open data streams when binded to all of them
        FutInfoListener ! Listener.Open(ReplicationParams(Combined))
        FutInfoListener ! Listener.Open(ReplicationParams(Combined))
        PosListener ! Listener.Open(ReplicationParams(Combined))

        // Start message processing
        Connection ! StartMessageProcessing(processMessagesTimeout);

        stay() using initialization

      case Some((SessionsState.Online, PositionsState.Online)) =>
        goto(WaitingOngoingSession) using Blank

      case _ => stay() using initialization
    }
  }

}