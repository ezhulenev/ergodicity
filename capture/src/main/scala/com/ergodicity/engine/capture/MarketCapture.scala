package com.ergodicity.engine.capture

import akka.util.duration._
import plaza2.{Connection => P2Connection}
import com.ergodicity.engine.plaza2.{ConnectionState, Connection}
import akka.actor._
import SupervisorStrategy._
import com.jacob.com.ComFailException
import akka.actor.FSM.{Failure, Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.engine.plaza2.Connection.Connect

case class CaptureFromConnection(props: ConnectionProperties)

sealed trait MarketCaptureState

case object Idle extends MarketCaptureState

case object Starting extends MarketCaptureState

case object Capturing extends MarketCaptureState


class MarketCapture(underlyingConnection: P2Connection) extends Actor with FSM[MarketCaptureState, Unit] {

  val connection = context.actorOf(Props(Connection(underlyingConnection)), "Connection")
  context.watch(connection)

  override val supervisorStrategy = AllForOneStrategy() {
    case _ : ComFailException => Stop
  }

  startWith(Idle, ())

  when(Idle) {
    case Event(CaptureFromConnection(ConnectionProperties(host, port, appName)), _) =>
      connection ! SubscribeTransitionCallBack(self)
      connection ! Connect(host, port, appName)
      goto(Starting)
  }

  when(Starting, stateTimeout = 10.second) {
    case Event(Transition(fsm, _, ConnectionState.Connected), _) if (fsm == connection) => goto(Capturing)
  }

  when(Capturing) {
    case _ => stay()
  }

  onTransition {
    case Idle -> Starting => log.info("Starting Market capture, waiting for connection established")
    case Starting -> Capturing => log.info("Begin capturing Market data")
  }
  
  whenUnhandled {
    case Event(Transition(fsm, _, _), _) if (fsm == connection) => stay()
    case Event(CurrentState(fsm, _), _) if (fsm == connection) => stay()
    case Event(Terminated(actor), _) if (actor == connection) => stop(Failure("Connection terminated"))
  }

  initialize

}
