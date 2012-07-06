package com.ergodicity.engine

import component.{OptInfoDataStreamComponent, FutInfoDataStreamComponent, ConnectionComponent}
import com.ergodicity.plaza2.DataStream.Open
import akka.actor.FSM.{Failure => FSMFailure, _}
import com.jacob.com.ComFailException
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import com.ergodicity.plaza2.{DataStream, DataStreamState, Connection, ConnectionState}
import com.ergodicity.plaza2.Connection.ProcessMessages
import com.ergodicity.core.Sessions.BindSessions
import com.ergodicity.core.{SessionsState, Sessions}


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

  case class InitializationState(futures: Option[DataStreamState], options: Option[DataStreamState]) extends TradingEngineData

}


case class StartTradingEngine(connection: ConnectionProperties)


class TradingEngine(processMessagesTimeout: Int) extends Actor with FSM[TradingEngineState, TradingEngineData] {
  this: Actor with FSM[TradingEngineState, TradingEngineData] with ConnectionComponent
    with FutInfoDataStreamComponent with OptInfoDataStreamComponent =>

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
  val FutInfo = context.actorOf(Props(DataStream(underlyingFutInfo)), "FuturesInfoDataStream")
  val OptInfo = context.actorOf(Props(DataStream(underlyingOptInfo)), "OptionsInfoDataStream")

  // Sessions tracking
  val Sessions = context.actorOf(Props(new Sessions(FutInfo, OptInfo)), "Sessions")

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

    case Event(Transition(Sessions, _, SessionsState.Binded), _) =>
      log.debug("Open FutInfo & OptInfo data streams")
      // Open data streams
      FutInfo ! Open(underlyingConnection)
      OptInfo ! Open(underlyingConnection)

      // Start message processing
      Connection ! ProcessMessages(processMessagesTimeout);

      stay()

    case Event(Transition(Sessions, _, SessionsState.Online), _) =>
      log.debug("Sessions online")
      goto(Trading)
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

    case Initializing -> Trading =>
      log.debug("Initialization finished, session contents loaded")
      Sessions ! UnsubscribeTransitionCallBack(self)
  }

}