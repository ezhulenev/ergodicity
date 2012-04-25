package com.ergodicity.engine.capture

import akka.util.duration._
import akka.actor._
import SupervisorStrategy._
import com.jacob.com.ComFailException
import akka.actor.FSM.{Failure, Transition, CurrentState, SubscribeTransitionCallBack}
import java.io.File
import plaza2.RequestType.CombinedDynamic
import plaza2.{TableSet, Connection => P2Connection, DataStream => P2DataStream}
import com.ergodicity.engine.plaza2.Connection.ProcessMessages
import com.ergodicity.engine.plaza2.DataStream._
import com.ergodicity.engine.plaza2.Repository._
import com.ergodicity.engine.plaza2.scheme._
import com.ergodicity.engine.core.model.{OptionContract, FutureContract}
import com.ergodicity.engine.plaza2._
import org.joda.time.format.DateTimeFormat
import scalaz._
import Scalaz._
import com.ergodicity.marketdb.model.{OrderPayload, Security, Market, TradePayload}
import java.net.{ConnectException, Socket}
import com.twitter.finagle.kestrel.protocol.Kestrel
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.kestrel.Client


case class Connect(props: ConnectionProperties)

sealed trait CaptureState

object CaptureState {
  case object Idle extends CaptureState

  case object Connecting extends CaptureState

  case object InitializingMarketContents extends CaptureState

  case object Capturing extends CaptureState
}

class MarketCapture(underlyingConnection: P2Connection, scheme: Plaza2Scheme,
                    repository: RevisionTracker, kestrel: KestrelConfig) extends Actor with FSM[CaptureState, MarketContents] {
  
  val Forts = Market("FORTS");
  val TimeFormat = DateTimeFormat.forPattern("yyyy/MM/dd HH:mm:ss.SSS")

  implicit val revisionTracker = repository;

  assertKestrelRunning()
  assert(new File(scheme.futInfo).exists(), "Futures info scheme doesn't exists")
  assert(new File(scheme.optInfo).exists(), "Options info scheme doesn't exists")
  assert(new File(scheme.ordLog).exists(), "Orders log scheme doesn't exists")
  assert(new File(scheme.futTrade).exists(), "Futures deals scheme doesn't exists")
  assert(new File(scheme.optTrade).exists(), "Options deals scheme doesn't exists")

  val FORTS_ORDLOG_REPL = "FORTS_ORDLOG_REPL"
  val FORTS_FUTTRADE_REPL = "FORTS_FUTTRADE_REPL"
  val FORTS_OPTTRADE_REPL = "FORTS_OPTTRADE_REPL"
  val FORTS_FUTINFO_REPL = "FORTS_FUTINFO_REPL"
  val FORTS_OPTINFO_REPL = "FORTS_OPTINFO_REPL"

  val connection = context.actorOf(Props(Connection(underlyingConnection)), "Connection")
  context.watch(connection)

  // Initial revisions
  val orderLogRevision = repository.revision(FORTS_ORDLOG_REPL, "orders_log")
  val futDealRevision = repository.revision(FORTS_FUTTRADE_REPL, "deal")
  val optDealRevision = repository.revision(FORTS_OPTTRADE_REPL, "deal")

  log.info("Initial stream revisions; OrderLog = " + orderLogRevision + "; FutDeal = " + futDealRevision + "; OptDeal = " + optDealRevision)

  // Track market contents
  var futSessContentsOnline = false
  val futSessContentsRepository = context.actorOf(Props(Repository[FutInfo.SessContentsRecord]), "FutSessContentsRepository")
  futSessContentsRepository ! SubscribeSnapshots(self)

  var optSessContentsOnline = false
  val optSessContentsRepository = context.actorOf(Props(Repository[OptInfo.SessContentsRecord]), "OptSessContentsRepository")
  optSessContentsRepository ! SubscribeSnapshots(self)

  // Kestrel client
  lazy val client = Client(ClientBuilder()
    .codec(Kestrel())
    .hosts(kestrel.host + ":" + kestrel.port)
    .hostConnectionLimit(kestrel.hostConnectionLimit)
    .buildFactory())

  // Create captures
  lazy val ordersBuncher = new OrdersBuncher(client, kestrel.ordersQueue)
  lazy val futuresBuncher = new TradesBuncher(client, kestrel.tradesQueue)
  lazy val optionsBuncher = new TradesBuncher(client, kestrel.tradesQueue)

  val ordersRevisionTracker = StreamRevisionTracker(FORTS_ORDLOG_REPL)
  val futuresRevisionTracker = StreamRevisionTracker(FORTS_FUTTRADE_REPL)
  val optionsRevisionTracker = StreamRevisionTracker(FORTS_OPTTRADE_REPL)

  val orderCapture = context.actorOf(Props(new MarketDbCapture(ordersRevisionTracker, ordersBuncher, orderLogRevision)((record: OrdLog.OrdersLogRecord) => {
    convertOrdersLog(record).getOrElse({throw new MarketDbCaptureException("Can't find isin for "+record)})
  })), "OrdersCapture")

  val futuresCapture = context.actorOf(Props(new MarketDbCapture(futuresRevisionTracker, futuresBuncher, futDealRevision)((record: FutTrade.DealRecord) => {
    convertFuturesDeal(record).getOrElse({throw new MarketDbCaptureException("Can't find isin for "+record)})
  })), "FuturesCapture")

  val optionsCapture = context.actorOf(Props(new MarketDbCapture(optionsRevisionTracker, optionsBuncher, optDealRevision)((record: OptTrade.DealRecord) => {
    convertOptionsDeal(record).getOrElse({throw new MarketDbCaptureException("Can't find isin for "+record)})
  })), "OptionsCapture")

  // Supervisor
  override val supervisorStrategy = AllForOneStrategy() {
    case _: ComFailException => Stop
    case _: MarketDbCaptureException => Stop
  }

  startWith(CaptureState.Idle, (None, None))

  when(CaptureState.Idle) {
    case Event(Connect(ConnectionProperties(host, port, appName)), _) =>
      connection ! SubscribeTransitionCallBack(self)
      connection ! Connection.Connect(host, port, appName)
      goto(CaptureState.Connecting)
  }

  when(CaptureState.Connecting, stateTimeout = 15.second) {
    case Event(Transition(fsm, _, ConnectionState.Connected), _) if (fsm == connection) => goto(CaptureState.InitializingMarketContents)
    case Event(FSM.StateTimeout, _) => stop(Failure("Connecting MarketCapture timed out"))
  }
  
  when(CaptureState.InitializingMarketContents, stateTimeout = 15.second) {
    case Event(Transition(ref, _, DataStreamState.Online), _) if (ref == futInfoStream) =>
      futSessContentsOnline = true;
      if (futSessContentsOnline && optSessContentsOnline) goto(CaptureState.Capturing) else stay()

    case Event(Transition(ref, _, DataStreamState.Online), _) if (ref == optInfoStream) =>
      optSessContentsOnline = true;
      if (futSessContentsOnline && optSessContentsOnline) goto(CaptureState.Capturing) else stay()

    case Event(FSM.StateTimeout, _) => stop(Failure("Initializing MarketCapture timed out"))
  }

  when(CaptureState.Capturing) {
    case _ => throw new RuntimeException("SHIT!")
  }

  onTransition {
    case CaptureState.Idle -> CaptureState.Connecting => log.info("Connecting Market capture, waiting for connection established")
    case CaptureState.Connecting -> CaptureState.InitializingMarketContents =>
      log.info("Initialize Market contents")

      futInfoStream ! JoinTable("fut_sess_contents", futSessContentsRepository, implicitly[Deserializer[FutInfo.SessContentsRecord]])
      futInfoStream ! SubscribeTransitionCallBack(self)
      futInfoStream ! Open(underlyingConnection)

      optInfoStream ! JoinTable("opt_sess_contents", optSessContentsRepository, implicitly[Deserializer[OptInfo.SessContentsRecord]])
      optInfoStream ! SubscribeTransitionCallBack(self)
      optInfoStream ! Open(underlyingConnection)

      connection ! ProcessMessages(100)

    case CaptureState.InitializingMarketContents -> CaptureState.Capturing =>
      log.info("Begin capturing Market data; Futures nbr = " + stateData._1.get.size + "; Options nbr = " + stateData._2.get.size)
      log.debug("Future contracts = "+stateData._1.get)
      log.debug("Option contracts = "+stateData._2.get)

      ordersDataStream ! JoinTable("orders_log", orderCapture, implicitly[Deserializer[OrdLog.OrdersLogRecord]])
      ordersDataStream ! SubscribeLifeNumChanges(self)
      ordersDataStream ! Open(underlyingConnection)

      futTradeDataStream ! JoinTable("deal", futuresCapture, implicitly[Deserializer[FutTrade.DealRecord]])
      futTradeDataStream ! SubscribeLifeNumChanges(self)
      futTradeDataStream ! Open(underlyingConnection)

      optTradeDataStream ! JoinTable("deal", optionsCapture, implicitly[Deserializer[OptTrade.DealRecord]])
      optTradeDataStream ! SubscribeLifeNumChanges(self)
      optTradeDataStream ! Open(underlyingConnection)

  }

  whenUnhandled {
    case Event(Transition(fsm, _, _), _) if (fsm == connection) => stay()
    case Event(CurrentState(fsm, _), _) if (fsm == connection) => stay()
    case Event(Terminated(actor), _) if (actor == connection) => stop(Failure("Connection terminated"))

    case Event(Transition(fsm, _, _), _) if (fsm == futInfoStream) => stay()
    case Event(CurrentState(fsm, _), _) if (fsm == futInfoStream) => stay()

    case Event(Transition(fsm, _, _), _) if (fsm == optInfoStream) => stay()
    case Event(CurrentState(fsm, _), _) if (fsm == optInfoStream) => stay()

    case Event(LifeNumChanged(ds, _), _) if (ds == ordersDataStream) => repository.reset(FORTS_ORDLOG_REPL); stay()
    case Event(LifeNumChanged(ds, _), _) if (ds == futTradeDataStream) => repository.reset(FORTS_FUTTRADE_REPL); stay()
    case Event(LifeNumChanged(ds, _), _) if (ds == optTradeDataStream) => repository.reset(FORTS_OPTTRADE_REPL); stay()

    // Handle session contents snapshots
    case Event(Snapshot(repo, data), (_, options)) if (repo == futSessContentsRepository) =>
      val futures = data.asInstanceOf[Iterable[FutInfo.SessContentsRecord]].foldLeft(Map[Int, FutureContract]()) {
        case (m, r) => m + (r.isinId -> com.ergodicity.engine.core.model.FutureConverter(r))
      }
      stay() using(Some(futures), options)

    case Event(Snapshot(repo, data), (futures, _)) if (repo == optSessContentsRepository) =>
      val options = data.asInstanceOf[Iterable[OptInfo.SessContentsRecord]].foldLeft(Map[Int, OptionContract]()) {
        case (m, r) => m + (r.isinId -> com.ergodicity.engine.core.model.OptionConverter(r))
      }
      stay() using(futures, Some(options))
  }

  initialize

  def convertOrdersLog(record: OrdLog.OrdersLogRecord) = {
    val futureIsin = stateData._1.flatMap {m => m.get(record.isin_id).map(_.isin)}
    val optionIsin = stateData._2.flatMap {m => m.get(record.isin_id).map(_.isin)}
    val isin = futureIsin <+> optionIsin
    isin.map {
      isin =>
        val deal = if (record.id_deal > 0) Some(record.deal_price) else None
        OrderPayload(Forts, Security(isin),
          record.id_ord, TimeFormat.parseDateTime(record.moment),
          record.status, record.action, record.dir, record.price, record.amount, record.amount_rest, deal)
    }
  }

  def convertFuturesDeal(record: FutTrade.DealRecord) = {
    val isin = stateData._1.flatMap {m => m.get(record.isin_id).map(_.isin)}
    isin.map {
      isin =>
        val nosystem = record.nosystem == 1 // Nosystem	0 - Рыночная сделка, 1 - Адресная сделка
        TradePayload(Forts, Security(isin), record.id_deal, record.price, record.amount, TimeFormat.parseDateTime(record.moment), nosystem)
    }
  }

  def convertOptionsDeal(record: OptTrade.DealRecord) = {
    val isin = stateData._2.flatMap {m => m.get(record.isin_id).map(_.isin)}
    isin.map {
      isin =>
        val nosystem = record.nosystem == 1 // Nosystem	0 - Рыночная сделка, 1 - Адресная сделка
        TradePayload(Forts, Security(isin), record.id_deal, record.price, record.amount, TimeFormat.parseDateTime(record.moment), nosystem)
    }
  }

  // Market Events data streams
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

  // Market Contents data streams
  lazy val futInfoStream = {
    val futInfoIni = new File(scheme.futInfo)
    val futInfoTableSet = TableSet(futInfoIni)
    val underlyingStream = P2DataStream(FORTS_FUTINFO_REPL, CombinedDynamic, futInfoTableSet)
    val futInfoStream = context.actorOf(Props(new DataStream(underlyingStream)), FORTS_FUTINFO_REPL)
    futInfoStream ! SetLifeNumToIni(futInfoIni)
    futInfoStream
  }

  lazy val optInfoStream = {
    val optInfoIni = new File(scheme.optInfo)
    val optInfoTableSet = TableSet(optInfoIni)
    val underlyingStream = P2DataStream(FORTS_OPTINFO_REPL, CombinedDynamic, optInfoTableSet)
    val optInfoStream = context.actorOf(Props(new DataStream(underlyingStream)), FORTS_OPTINFO_REPL)
    optInfoStream ! SetLifeNumToIni(optInfoIni)
    optInfoStream
  }

  private def assertKestrelRunning() {
    try {
      new Socket(kestrel.host, kestrel.port)
      kestrel
    } catch {
      case e: ConnectException =>
        println("Error: Kestrel must be running on host " + kestrel.host + "; port " + kestrel.port)
        System.exit(1)
    }
  }
}
