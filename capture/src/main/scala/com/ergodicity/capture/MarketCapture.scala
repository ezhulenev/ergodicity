package com.ergodicity.capture

import akka.actor._
import SupervisorStrategy._
import com.ergodicity.marketdb.model.{Security => MarketDbSecurity}
import org.joda.time.DateTime
import akka.actor.FSM.{Normal, Transition, UnsubscribeTransitionCallBack, SubscribeTransitionCallBack}
import akka.util.duration._
import com.ergodicity.cgate._
import config.Replication.ReplicationMode.Combined
import config.Replication.ReplicationParams
import scalaz._
import Scalaz._
import com.ergodicity.capture.MarketDbCapture.ConvertToMarketDb
import scheme.OrdLog.orders_log
import scala.Some
import com.ergodicity.cgate.StreamEvent.ReplState
import akka.actor.AllForOneStrategy
import com.ergodicity.marketdb.model.Market
import com.ergodicity.cgate.DataStream.{DataStreamClosed, SubscribeReplState}
import com.ergodicity.core.{Isin, Security}
import com.ergodicity.marketdb.model.TradePayload
import akka.actor.Terminated
import com.ergodicity.marketdb.model.OrderPayload
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate.scheme._
import ru.micexrts.cgate.{CGateException, Listener => CGListener, Connection => CGConnection}
import java.util.concurrent.TimeUnit


case class MarketCaptureException(msg: String) extends RuntimeException(msg)

object MarketCapture {
  val Forts = Market("FORTS")

  case object Capture

  case object ShutDown

}

sealed trait CaptureState

object CaptureState {

  case object Idle extends CaptureState

  case object Connecting extends CaptureState

  case object InitializingMarketContents extends CaptureState

  case object Capturing extends CaptureState

  case object ShuttingDown extends CaptureState

}

sealed trait CaptureData

object CaptureData {

  case class Contents(contents: Map[Int, Security]) extends CaptureData

  case class StreamStates(futTrade: Option[ReplState] = None,
                          optTrade: Option[ReplState] = None,
                          ordLog: Option[ReplState] = None) extends CaptureData

}

trait CaptureConnection {
  self: MarketCapture =>

  def underlyingConnection: CGConnection
}

trait UnderlyingListeners {
  self: MarketCapture =>

  def underlyingFutInfoListener: CGListener

  def underlyingOptInfoListener: CGListener

  def underlyingFutTradeListener: CGListener

  def underlyingOptTradeListener: CGListener

  def underlyingOrdLogListener: CGListener
}

trait UnderlyingListenersImpl extends UnderlyingListeners {
  self: MarketCapture with CaptureConnection =>

  lazy val underlyingFutInfoListener = new CGListener(underlyingConnection, replication.futInfo(), new DataStreamSubscriber(FutInfoStream))

  lazy val underlyingOptInfoListener = new CGListener(underlyingConnection, replication.optInfo(), new DataStreamSubscriber(OptInfoStream))

  lazy val underlyingFutTradeListener = new CGListener(underlyingConnection, replication.futTrade(), new DataStreamSubscriber(FutTradeStream))

  lazy val underlyingOptTradeListener = new CGListener(underlyingConnection, replication.optTrade(), new DataStreamSubscriber(OptTradeStream))

  lazy val underlyingOrdLogListener = new CGListener(underlyingConnection, replication.ordLog(), new DataStreamSubscriber(OrdLogStream))
}

trait CaptureListeners {
  self: MarketCapture with UnderlyingListeners =>

  def FutInfoListener: ActorRef

  def OptInfoListener: ActorRef

  def FutTradeListener: ActorRef

  def OptTradeListener: ActorRef

  def OrdLogListener: ActorRef
}

trait CaptureListenersImpl extends CaptureListeners {
  self: MarketCapture with UnderlyingListeners =>

  val FutInfoListener = context.actorOf(Props(new Listener(underlyingFutInfoListener)).withDispatcher(ReplicationDispatcher), "FutInfoListener")
  val OptInfoListener = context.actorOf(Props(new Listener(underlyingOptInfoListener)).withDispatcher(ReplicationDispatcher), "OptInfoListener")
  val FutTradeListener = context.actorOf(Props(new Listener(underlyingFutTradeListener)).withDispatcher(ReplicationDispatcher), "FutTradeListener")
  val OptTradeListener = context.actorOf(Props(new Listener(underlyingOptTradeListener)).withDispatcher(ReplicationDispatcher), "OptTradeListener")
  val OrdLogListener = context.actorOf(Props(new Listener(underlyingOrdLogListener, Some(100.millis))).withDispatcher(ReplicationDispatcher), "OrdLogListener")
}

class MarketCapture(val replication: ReplicationScheme,
                    repository: ReplicationStateRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository,
                    kestrel: KestrelConfig) extends Actor with FSM[CaptureState, CaptureData] {
  capture: CaptureConnection with UnderlyingListeners with CaptureListeners =>

  import MarketCapture._

  val ReplicationDispatcher = "capture.dispatchers.replicationDispatcher"

  implicit def SecuritySemigroup: Semigroup[Security] = semigroup {
    case (s1, s2) => s2
  }

  import CaptureData._

  implicit val revisionTracker = repository

  // Kestrel client
  val client = kestrel()

  // CGAte connection
  val connection = context.actorOf(Props(Connection(underlyingConnection)).withDispatcher(ReplicationDispatcher), "Connection")
  context.watch(connection)

  // Data Streams
  val FutInfoStream = context.actorOf(Props(new DataStream), "FutInfoDataStream")
  val OptInfoStream = context.actorOf(Props(new DataStream), "OptInfoDataStream")
  val FutTradeStream = context.actorOf(Props(new DataStream), "FutTradeDataStream")
  val OptTradeStream = context.actorOf(Props(new DataStream), "OptTradeDataStream")
  val OrdLogStream = context.actorOf(Props(new DataStream), "OrdLogDataStream")

  // Subscribe for ReplState
  val streams = FutInfoStream :: OptInfoStream :: FutTradeStream :: OptTradeStream :: OrdLogStream :: Nil
  streams.foreach(_ ! SubscribeReplState(self))

  // Create captures
  implicit val ConvertOrder = new ConvertToMarketDb[OrdLog.orders_log, OrderPayload] {
    def apply(in: orders_log) = convertOrdersLog(in).toSuccess("Failed to find isin for id = " + in.get_isin_id() + "; Session id = " + in.get_sess_id())
  }

  implicit val ConvertFutureDeal = new ConvertToMarketDb[FutTrade.deal, TradePayload] {
    def apply(in: FutTrade.deal) = convertFuturesDeal(in).toSuccess("Failed to find isin for id = " + in.get_isin_id() + "; Session id = " + in.get_sess_id())
  }

  implicit val ConvertOptionDeal = new ConvertToMarketDb[OptTrade.deal, TradePayload] {
    def apply(in: OptTrade.deal) = convertOptionsDeal(in).toSuccess("Failed to find isin for id = " + in.get_isin_id() + "; Session id = " + in.get_sess_id())
  }

  lazy val ordersBuncher = new OrdersBuncher(client, kestrel.ordersQueue)
  lazy val futureDealsBuncher = new TradesBuncher(client, kestrel.tradesQueue)
  lazy val optionDealsBuncher = new TradesBuncher(client, kestrel.tradesQueue)

  import com.ergodicity.cgate.Protocol._

  val orderCapture = context.actorOf(Props(new MarketDbCapture[OrdLog.orders_log, OrderPayload](OrdLog.orders_log.TABLE_INDEX, OrdLogStream)(ordersBuncher)), "OrdersCapture")
  val futuresCapture = context.actorOf(Props(new MarketDbCapture[FutTrade.deal, TradePayload](FutTrade.deal.TABLE_INDEX, FutTradeStream)(futureDealsBuncher)), "FuturesCapture")
  val optionsCapture = context.actorOf(Props(new MarketDbCapture[OptTrade.deal, TradePayload](OptTrade.deal.TABLE_INDEX, OptTradeStream)(optionDealsBuncher)), "OptionsCapture")

  // Market Contents capture
  val marketContentsCapture = context.actorOf(Props(new MarketContentsCapture(FutInfoStream, OptInfoStream, repository)), "MarketContentsCapture")
  marketContentsCapture ! SubscribeMarketContents(self)

  // Supervisor
  override val supervisorStrategy = AllForOneStrategy() {
    case _: CGateException => Stop
    case _: MarketCaptureException => Stop
  }

  var lastContents: Option[Contents] = None

  startWith(CaptureState.Idle, Contents(Map()))

  when(CaptureState.Idle) {
    case Event(Capture, _) =>
      connection ! SubscribeTransitionCallBack(self)
      connection ! Connection.Open
      goto(CaptureState.Connecting)
  }

  when(CaptureState.Connecting, stateTimeout = 15.second) {
    case Event(Transition(ref, _, Active), _) if (ref == connection) =>
      connection ! UnsubscribeTransitionCallBack(self)
      goto(CaptureState.InitializingMarketContents)

    case Event(FSM.StateTimeout, _) =>
      log.error("Connecting timed out")
      throw new MarketCaptureException("Connecting timed out")
  }

  when(CaptureState.InitializingMarketContents, stateTimeout = 15.second) {
    case Event(MarketContentsInitialized, _) => goto(CaptureState.Capturing)

    case Event(FSM.StateTimeout, _) =>
      log.error("Initialization timed out")
      throw new MarketCaptureException("Initialization timed out")
  }

  when(CaptureState.Capturing) {
    case Event(ShutDown, _) => goto(CaptureState.ShuttingDown) using StreamStates()
  }

  when(CaptureState.ShuttingDown, stateTimeout = 30.seconds) {
    case Event(DataStreamClosed(FutTradeStream, state), s: StreamStates) =>
      repository.setReplicationState(replication.futTrade.stream, state)
      handleStreamState(s.copy(futTrade = Some(ReplState(state))))

    case Event(DataStreamClosed(OptTradeStream, state), s: StreamStates) =>
      repository.setReplicationState(replication.optTrade.stream, state)
      handleStreamState(s.copy(optTrade = Some(ReplState(state))))

    case Event(DataStreamClosed(OrdLogStream, state), s: StreamStates) =>
      repository.setReplicationState(replication.ordLog.stream, state)
      handleStreamState(s.copy(ordLog = Some(ReplState(state))))

    case Event(Terminated(ref), _) if (ref == connection) =>
      log.error("Connection terminated in ShuttingDown state")
      stop(Normal)

    case Event(FSM.StateTimeout, _) =>
      closeConnection()
      stay()
  }

  onTransition {
    case CaptureState.Idle -> CaptureState.Connecting =>
      log.info("Connecting Market capture, waiting for connection established")

    case CaptureState.Connecting -> CaptureState.InitializingMarketContents =>
      log.info("Initialize Market contents")
      connection ! StartMessageProcessing(100.millis)
      FutInfoListener ! Listener.Open(ReplicationParams(Combined))
      OptInfoListener ! Listener.Open(ReplicationParams(Combined))

    case CaptureState.InitializingMarketContents -> CaptureState.Capturing =>
      log.info("Begin capturing Market data")
      log.debug("Market contents size = " + stateData.asInstanceOf[Contents].contents.size)

      val orderLogState = repository.replicationState(replication.ordLog.stream)
      val futTradeState = repository.replicationState(replication.futTrade.stream)
      val optTradeState = repository.replicationState(replication.optTrade.stream)

      log.info("Initial stream revisions; OrderLog = " + orderLogState + "; FutDeal = " + futTradeState + "; OptDeal = " + optTradeState)

      OrdLogListener ! Listener.Open(ReplicationParams(Combined, state = orderLogState.map(StreamEvent.ReplState(_))))
      FutTradeListener ! Listener.Open(ReplicationParams(Combined, state = futTradeState.map(StreamEvent.ReplState(_))))
      OptTradeListener ! Listener.Open(ReplicationParams(Combined, state = optTradeState.map(StreamEvent.ReplState(_))))

    case CaptureState.Capturing -> CaptureState.ShuttingDown =>
      log.info("Shutting down Market Capture; Close all listeners!")
      cgListeners.foreach(_ ! Listener.Close)
  }

  whenUnhandled {
    case Event(Terminated(ref), _) if (ref == connection) =>
      log.error("Connection terminated")
      throw new MarketCaptureException("Connection terminated")

    // Handle Market contents updates
    case Event(FuturesContents(futures), Contents(contents)) => stay() using Contents(contents <+> futures)
    case Event(OptionsContents(options), Contents(contents)) => stay() using Contents(contents <+> options)
  }

  initialize

  private def convertOrdersLog(record: OrdLog.orders_log): Option[OrderPayload] = {
    val isin = safeIsin(record.get_isin_id())
    isin.map {
      isin =>
        val deal: Option[BigDecimal] = if (record.get_id_deal() > 0) Some(record.get_deal_price()) else None
        OrderPayload(Forts, MarketDbSecurity(isin.isin),
          record.get_id_ord(), new DateTime(record.get_moment()),
          record.get_status(), record.get_action(), record.get_dir(), record.get_price(), record.get_amount(), record.get_amount_rest(), deal)
    }
  }

  private def convertFuturesDeal(record: FutTrade.deal): Option[TradePayload] = {
    val isin = safeIsin(record.get_isin_id())
    isin.map {
      isin =>
        val nosystem = record.get_nosystem() == 1 // Nosystem	0 - Рыночная сделка, 1 - Адресная сделка
        TradePayload(Forts, MarketDbSecurity(isin.isin), record.get_id_deal(), record.get_price(), record.get_amount(), new DateTime(record.get_moment()), nosystem)
    }
  }

  private def convertOptionsDeal(record: OptTrade.deal): Option[TradePayload] = {
    val isin = safeIsin(record.get_isin_id())
    isin.map {
      isin =>
        val nosystem = record.get_nosystem() == 1 // Nosystem	0 - Рыночная сделка, 1 - Адресная сделка
        TradePayload(Forts, MarketDbSecurity(isin.isin), record.get_id_deal(), record.get_price(), record.get_amount(), new DateTime(record.get_moment()), nosystem)
    }
  }

  private def safeIsin(id: Int): Option[Isin] = {
    val cur = stateData match {
      case c: Contents => lastContents = Some(c); Some(c)
      case _ => None
    }
    (cur <+> lastContents).flatMap(_.contents.get(id).map(_.isin))
  }

  protected def handleStreamState(state: StreamStates): State = {
    (state.futTrade.<***>(state.optTrade, state.ordLog))((_, _, _)) match {
      case states@Some((_, _, _)) =>
        log.debug("Streams shutted down in states = " + states)
        closeConnection()
        stay()
      case _ =>
        log.debug("Waiting for all streams closed in state = " + state)
        stay() using state
    }
  }

  def closeConnection() {
    log.info("Close listeners: " + cgListeners)
    cgListeners.foreach(_ ! Listener.Dispose)
    // Let all listeners to be closed before connection
    Thread.sleep(TimeUnit.SECONDS.toMillis(3))
    connection ! Connection.Close
    connection ! Connection.Dispose
  }

  def cgListeners = (FutInfoListener :: OptInfoListener :: FutTradeListener :: OptTradeListener :: OrdLogListener :: Nil)
}