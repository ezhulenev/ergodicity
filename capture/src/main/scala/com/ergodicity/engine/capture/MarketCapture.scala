package com.ergodicity.engine.capture

import akka.util.duration._
import akka.actor._
import SupervisorStrategy._
import com.jacob.com.ComFailException
import akka.actor.FSM.{Failure, Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.engine.plaza2.{DataStream, ConnectionState, Connection}
import java.io.File
import plaza2.RequestType.CombinedDynamic
import plaza2.{TableSet, Connection => P2Connection, DataStream => P2DataStream}
import com.ergodicity.engine.plaza2.scheme.{OrdLog, Deserializer}
import com.ergodicity.engine.plaza2.DataStream.Open._
import com.ergodicity.engine.plaza2.DataStream.{Open, JoinTable, SetLifeNumToIni}
import com.ergodicity.engine.plaza2.Connection.ProcessMessages

case class Connect(props: ConnectionProperties)


sealed trait CaptureState

case object Idle extends CaptureState

case object Starting extends CaptureState

case object Capturing extends CaptureState


class MarketCapture(underlyingConnection: P2Connection) extends Actor with FSM[CaptureState, Unit] {

  val connection = context.actorOf(Props(Connection(underlyingConnection)), "Connection")
  context.watch(connection)

  // Capture Full Orders Log
  lazy val ordersDataStream = {
    val ini = new File("capture/scheme/OrdLog.ini")
    val tableSet = TableSet(ini)
    val underlyingStream = P2DataStream("FORTS_ORDLOG_REPL", CombinedDynamic, tableSet)
    val ordersDataStream = context.actorOf(Props(DataStream(underlyingStream)), "FORTS_ORDLOG_REPL")
    ordersDataStream ! SetLifeNumToIni(ini)
    ordersDataStream
  }

  val orderCapture = context.actorOf(Props(new OrdersCapture), "OrdersCapture")

  // Supervisor
  override val supervisorStrategy = AllForOneStrategy() {
    case _: ComFailException => Stop
    case _: OrdersCaptureException => Stop
  }


  startWith(Idle, ())

  when(Idle) {
    case Event(Connect(ConnectionProperties(host, port, appName)), _) =>
      connection ! SubscribeTransitionCallBack(self)
      connection ! Connection.Connect(host, port, appName)
      goto(Starting)
  }

  when(Starting, stateTimeout = 15.second) {
    case Event(Transition(fsm, _, ConnectionState.Connected), _) if (fsm == connection) => goto(Capturing)
    case Event(FSM.StateTimeout, _) => stop(Failure("Starting MarketCapture timed out"))
  }

  when(Capturing) {
    case _ => stay()
  }

  onTransition {
    case Idle -> Starting => log.info("Starting Market capture, waiting for connection established")
    case Starting -> Capturing =>
      log.info("Begin capturing Market data")
      ordersDataStream ! JoinTable("orders_log", orderCapture, implicitly[Deserializer[OrdLog.OrdersLogRecord]])
      ordersDataStream ! Open(underlyingConnection)
      context.system.scheduler.scheduleOnce(3 seconds) {
        connection ! ProcessMessages(100)
      }
  }

  whenUnhandled {
    case Event(Transition(fsm, _, _), _) if (fsm == connection) => stay()
    case Event(CurrentState(fsm, _), _) if (fsm == connection) => stay()
    case Event(Terminated(actor), _) if (actor == connection) => stop(Failure("Connection terminated"))
  }

  initialize

}
