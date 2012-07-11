package com.ergodicity.engine

import com.ergodicity.plaza2.DataStream.Open
import akka.actor.FSM.{Failure => FSMFailure, _}
import com.jacob.com.ComFailException
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import com.ergodicity.plaza2.{DataStream, Connection, ConnectionState}
import com.ergodicity.plaza2.Connection.ProcessMessages
import com.ergodicity.core.Sessions.BindSessions
import com.ergodicity.core.{SessionsState, Sessions}
import com.ergodicity.core.position.{Positions, PositionsState}
import com.ergodicity.core.position.Positions.BindPositions
import com.ergodicity.core.broker.Broker
import component._
import org.mockito.Mockito._
import plaza2.MessageFactory


sealed trait TradingEngineState

object TradingEngineState {

  case object Idle extends TradingEngineState

  case object Connecting extends TradingEngineState

  case object Initializing extends TradingEngineState

  case object Trading extends TradingEngineState

}

sealed trait TradingEngineData

object TradingEngineData {

  case object Blank extends TradingEngineData

  case class InitializationState(sessions: Option[SessionsState], positions: Option[PositionsState]) extends TradingEngineData

}


case class StartTradingEngine(connection: ConnectionProperties)


class TradingEngine(clientCode: String, processMessagesTimeout: Int) extends Actor with FSM[TradingEngineState, TradingEngineData] {
  this: Actor with FSM[TradingEngineState, TradingEngineData] with ConnectionComponent
    with FutInfoDataStreamComponent with OptInfoDataStreamComponent with PosDataStreamComponent with MessageFactoryComponent =>

  import TradingEngineState._
  import TradingEngineData._

  log.info("Create TradingEngine; Connection = " + underlyingConnection + "; FutInfo = " + underlyingFutInfo + "; OptInfo = " + underlyingOptInfo)

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
  val FutInfo = context.actorOf(Props(DataStream(underlyingFutInfo, futInfoIni)), "FuturesInfoDataStream")
  val OptInfo = context.actorOf(Props(DataStream(underlyingOptInfo, optInfoIni)), "OptionsInfoDataStream")
  val Pos = context.actorOf(Props(DataStream(underlyingPos, posIni)), "PositionsDataStream")

  // Sessions tracking
  val Sessions = context.actorOf(Props(new Sessions(FutInfo, OptInfo)), "Sessions")

  // Positions tracking
  val Positions = context.actorOf(Props(new Positions(Pos)), "Positions")

  // Broker
  val Broker = context.actorOf(Props(new Broker(clientCode, underlyingConnection)), "Broker")

  startWith(Idle, Blank)

  when(Idle) {
    case Event(StartTradingEngine(props@ConnectionProperties(host, port, appName)), _) =>
      log.info("Connect to host = " + host + ", port = " + port + ", appName = " + appName)
      Connection ! props.asConnect
      goto(Connecting)
  }

  when(Connecting) {
    case Event(Transition(Connection, _, ConnectionState.Connected), _) => goto(Initializing) using InitializationState(None, None)
    case Event(Terminated(Connection), _) => stop(FSMFailure("Connection terminated"))
  }

  when(Initializing) {
    case Event(Transition(Sessions, _, state: SessionsState), initializing: InitializationState) =>
      handleInitializationState(initializing.copy(sessions = Some(state)))

    case Event(Transition(Positions, _, state: PositionsState), initializing: InitializationState) =>
      handleInitializationState(initializing.copy(positions = Some(state)))
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

    case Initializing -> Trading =>
      log.debug("Initialization finished, go to Trading!")
      Sessions ! UnsubscribeTransitionCallBack(self)
      Positions ! UnsubscribeTransitionCallBack(self)
  }

  private def handleInitializationState(initialization: InitializationState): State = {
    import scalaz._
    import Scalaz._

    (initialization.sessions |@| initialization.positions) {(_, _)} match {
      case Some((SessionsState.Binded, PositionsState.Binded)) =>
        log.debug("Open Sessions & Positions underlying streams")
        // Open data streams when binded to all of them
        FutInfo ! Open(underlyingConnection)
        OptInfo ! Open(underlyingConnection)
        Pos ! Open(underlyingConnection)

        // Start message processing
        Connection ! ProcessMessages(processMessagesTimeout);

        stay() using initialization

      case Some((SessionsState.Online, PositionsState.Online)) =>
        goto(Trading) using Blank

      case _ => stay() using initialization
    }
  }

}