package com.ergodicity.engine

import scalaz._
import Scalaz._
import component.{OptInfoDataStreamComponent, FutInfoDataStreamComponent, ConnectionComponent}
import com.ergodicity.core.Sessions
import com.ergodicity.core.Sessions.{BindOptInfoRepl, BindFutInfoRepl}
import com.ergodicity.plaza2.DataStream.Open
import akka.actor.FSM.{Failure => FSMFailure, _}
import com.jacob.com.ComFailException
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import com.ergodicity.plaza2.{DataStream, DataStreamState, Connection, ConnectionState}
import com.ergodicity.plaza2.Connection.ProcessMessages


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
  val Sessions = context.actorOf(Props(new Sessions), "Sessions")

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
    // Handle FutInfo and OptInfo data streams updates
    case Event(CurrentState(FutInfo, state: DataStreamState), initialization: InitializationState) =>
      handleInitializationState(initialization.copy(futures = Some(state)))

    case Event(CurrentState(OptInfo, state: DataStreamState), initialization: InitializationState) =>
      handleInitializationState(initialization.copy(options = Some(state)))

    case Event(Transition(FutInfo, _, state: DataStreamState), initialization: InitializationState) =>
      handleInitializationState(initialization.copy(futures = Some(state)))

    case Event(Transition(OptInfo, _, state: DataStreamState), initialization: InitializationState) =>
      handleInitializationState(initialization.copy(options = Some(state)))
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
      Sessions ! BindFutInfoRepl(FutInfo)
      Sessions ! BindOptInfoRepl(OptInfo)

      // Subscribe for state updates
      FutInfo ! SubscribeTransitionCallBack(self)
      OptInfo ! SubscribeTransitionCallBack(self)

      Thread.sleep(100)

      // Open data streams
      FutInfo ! Open(underlyingConnection)
      OptInfo ! Open(underlyingConnection)

      // Start message processing
      Connection ! ProcessMessages(processMessagesTimeout);

    case Initializing -> Trading =>
      log.debug("Initialization finished, session contents loaded")
      FutInfo ! UnsubscribeTransitionCallBack(self)
      OptInfo ! UnsubscribeTransitionCallBack(self)
  }

  protected def handleInitializationState(state: InitializationState): State = (state.futures <**> state.options) {(_, _)} match {
    case Some((DataStreamState.Online, DataStreamState.Online)) => goto(Trading) using Blank
    case _ => stay() using state
  }

}