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
import com.ergodicity.engine.plaza2.Connection.ProcessMessages
import com.ergodicity.engine.plaza2.scheme.{OptTrade, FutTrade, OrdLog, Deserializer}
import scalax.io.{Resource, Seekable}
import com.ergodicity.engine.plaza2.DataStream._

case class Connect(props: ConnectionProperties)


sealed trait CaptureState

case object Idle extends CaptureState

case object Starting extends CaptureState

case object Capturing extends CaptureState


class MarketCapture(underlyingConnection: P2Connection, scheme: CaptureScheme, repository: RevisionTracker) extends Actor with FSM[CaptureState, Unit] {

  assert(new File(scheme.ordLog).exists(), "Orders log scheme doesn't exists")
  assert(new File(scheme.futTrade).exists(), "Futures deals scheme doesn't exists")
  assert(new File(scheme.optTrade).exists(), "Options deals scheme doesn't exists")
  
  val FORTS_ORDLOG_REPL = "FORTS_ORDLOG_REPL"
  val FORTS_FUTTRADE_REPL = "FORTS_FUTTRADE_REPL"
  val FORTS_OPTTRADE_REPL = "FORTS_OPTTRADE_REPL"

  val connection = context.actorOf(Props(Connection(underlyingConnection)), "Connection")
  context.watch(connection)

  // Initial revisions
  val orderLogRevision = repository.revision(FORTS_ORDLOG_REPL, "orders_log")
  val futDealRevision = repository.revision(FORTS_FUTTRADE_REPL, "deal")
  val optDealRevision = repository.revision(FORTS_OPTTRADE_REPL, "deal")

  log.info("Initial stream revisions; OrderLog = " + orderLogRevision + "; FutDeal = " + futDealRevision + "; OptDeal = " + optDealRevision)

  // Capture Full Orders Log
  lazy val ordersDataStream = {
    val ini = new File(scheme.ordLog)

    val tableSet = TableSet(ini)
    orderLogRevision.foreach {rev =>
      tableSet.setRevision("orders_log", rev+1)
    }

    val underlyingStream = P2DataStream(FORTS_ORDLOG_REPL, CombinedDynamic, tableSet)
    val ordersDataStream = context.actorOf(Props(DataStream(underlyingStream)), FORTS_ORDLOG_REPL)
    ordersDataStream ! SetLifeNumToIni(ini)
    ordersDataStream
  }


  lazy val futTradeDataStream = {
    val ini = new File(scheme.futTrade)
    val tableSet = TableSet(ini)
    futDealRevision.foreach {rev =>
      tableSet.setRevision("deal", rev+1)
    }

    val underlyingStream = P2DataStream(FORTS_FUTTRADE_REPL, CombinedDynamic, tableSet)
    val futTradeDataStream = context.actorOf(Props(DataStream(underlyingStream)), FORTS_FUTTRADE_REPL)
    futTradeDataStream ! SetLifeNumToIni(ini)
    futTradeDataStream
  }

  lazy val optTradeDataStream = {
    val ini = new File(scheme.optTrade)
    val tableSet = TableSet(ini)
    optDealRevision.foreach {rev =>
      tableSet.setRevision("deal", rev+1)
    }

    val underlyingStream = P2DataStream(FORTS_OPTTRADE_REPL, CombinedDynamic, tableSet)
    val optTradeDataStream = context.actorOf(Props(DataStream(underlyingStream)), FORTS_OPTTRADE_REPL)
    optTradeDataStream ! SetLifeNumToIni(ini)
    optTradeDataStream
  }

  // Create captures
  val orderCapture = context.actorOf(Props(new DataStreamCapture[OrdLog.OrdersLogRecord](orderLogRevision)((rec:OrdLog.OrdersLogRecord) => captureOrders(rec))), "OrdersCapture")
  val futuresCapture = context.actorOf(Props(new DataStreamCapture[FutTrade.DealRecord](futDealRevision)((rec:FutTrade.DealRecord) => captureFutures(rec))), "FuturesCapture")
  val optionsCapture = context.actorOf(Props(new DataStreamCapture[OptTrade.DealRecord](optDealRevision)((rec:OptTrade.DealRecord) => captureOptions(rec))), "OptionsCapture")

  // Track table revisions
  orderCapture ! TrackRevisions(repository, FORTS_ORDLOG_REPL)
  futuresCapture ! TrackRevisions(repository, FORTS_FUTTRADE_REPL)
  optionsCapture ! TrackRevisions(repository, FORTS_OPTTRADE_REPL)

  // Supervisor
  override val supervisorStrategy = AllForOneStrategy() {
    case _: ComFailException => Stop
    case _: DataStreamCaptureException => Stop
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
      ordersDataStream ! SubscribeLifeNumChanges(self)
      ordersDataStream ! Open(underlyingConnection)

      futTradeDataStream ! JoinTable("deal", futuresCapture, implicitly[Deserializer[FutTrade.DealRecord]])
      futTradeDataStream ! SubscribeLifeNumChanges(self)
      futTradeDataStream ! Open(underlyingConnection)

      optTradeDataStream ! JoinTable("deal", optionsCapture, implicitly[Deserializer[OptTrade.DealRecord]])
      optTradeDataStream ! SubscribeLifeNumChanges(self)
      optTradeDataStream ! Open(underlyingConnection)

      // Be sure DataStream's opened
      context.system.scheduler.scheduleOnce(1 seconds) {
        connection ! ProcessMessages(100)
      }
  }

  whenUnhandled {
    case Event(Transition(fsm, _, _), _) if (fsm == connection) => stay()
    case Event(CurrentState(fsm, _), _) if (fsm == connection) => stay()
    case Event(Terminated(actor), _) if (actor == connection) => stop(Failure("Connection terminated"))

    case Event(LifeNumChanged(ds, _), _) if (ds == ordersDataStream) => repository.reset(FORTS_ORDLOG_REPL); stay()
    case Event(LifeNumChanged(ds, _), _) if (ds == futTradeDataStream) => repository.reset(FORTS_FUTTRADE_REPL); stay()
    case Event(LifeNumChanged(ds, _), _) if (ds == optTradeDataStream) => repository.reset(FORTS_OPTTRADE_REPL); stay()
  }

  initialize

  def captureOrders(record: OrdLog.OrdersLogRecord) {
/*    val file: Seekable = Resource.fromFile("C:\\Temp\\OrdersLog\\orders.txt")
    file.append(record.toString+"\r\n")*/
  }

  def captureFutures(record: FutTrade.DealRecord) {
    val file: Seekable = Resource.fromFile("C:\\Temp\\OrdersLog\\futures.txt")
    file.append(record.toString+"\r\n")
  }

  def captureOptions(record: OptTrade.DealRecord) {
    val file: Seekable = Resource.fromFile("C:\\Temp\\OrdersLog\\options.txt")
    file.append(record.toString+"\r\n")
  }

}
