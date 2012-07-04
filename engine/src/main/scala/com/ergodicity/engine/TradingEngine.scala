package com.ergodicity.engine

import component.ConnectionComponent
import com.ergodicity.plaza2.ConnectionState
import akka.actor.{Terminated, Props, FSM, Actor}
import akka.actor.FSM.{Failure, Transition, SubscribeTransitionCallBack}

sealed trait TradingEngineState

object TradingEngineState {

  case object Idle extends TradingEngineState

  case object Connecting extends TradingEngineState

  case object Initializing extends TradingEngineState

  case object Trading extends TradingEngineState

}


case class StartTradingEngine(connection: ConnectionProperties)


class TradingEngine extends Actor with FSM[TradingEngineState, Unit] {
  this: Actor with FSM[TradingEngineState, Unit] with ConnectionComponent =>

  import TradingEngineState._

  // Create connection
  val Connection = context.actorOf(Props(connectionCreator), "Connection")
  context.watch(Connection)
  Connection ! SubscribeTransitionCallBack(self)

  startWith(Idle, Unit)

  when(Idle) {
    case Event(StartTradingEngine(props@ConnectionProperties(host, port, appName)), _) =>
      log.info("Connect to host = " + host + ", port = " + port + ", appName = " + appName)
      Connection ! props.asConnect
      goto(Connecting)
  }

  when(Connecting) {
    case Event(Transition(Connection, _, ConnectionState.Connected), _) => goto(Initializing)
    case Event(Terminated(Connection), _) => stop(Failure("Connection terminated"))
  }

  when(Initializing) {
    case _ => stay()
  }

  onTransition {
    case Idle -> Connecting => log.debug("Establishing connection")
    case Connecting -> Initializing => log.debug("Initializing Trading Engine")
  }
}