package com.ergodicity.capture

import akka.util.duration._
import akka.actor._
import SupervisorStrategy._
import com.jacob.com.ComFailException
import com.ergodicity.marketdb.model.{Security => MarketDbSecurity}
import org.joda.time.DateTime
import ru.micexrts.cgate.{Listener => CGListener, Connection => CGConnection}
import akka.actor.FSM.{Failure => FSMFailure}
import com.ergodicity.cgate._
import config.Replication.ReplicationMode.Combined
import config.Replication.ReplicationParams
import scalaz._
import Scalaz._
import com.ergodicity.capture.MarketDbCapture.ConvertToMarketDb
import scheme.OrdLog.orders_log
import akka.actor.FSM.Transition
import akka.actor.FSM.UnsubscribeTransitionCallBack
import scala.Some
import com.ergodicity.cgate.StreamEvent.ReplState
import com.ergodicity.core.common.FullIsin
import akka.actor.AllForOneStrategy
import com.ergodicity.marketdb.model.Market
import com.ergodicity.cgate.DataStream.DataStreamReplState
import com.ergodicity.cgate.DataStream.SubscribeReplState
import com.ergodicity.core.common.Security
import com.ergodicity.marketdb.model.TradePayload
import akka.actor.Terminated
import com.ergodicity.marketdb.model.OrderPayload
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate.scheme._
import com.ergodicity.cgate.Protocol._


case class MarketCaptureException(msg: String) extends RuntimeException(msg)

case object Capture

case object ShutDown

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

  case class StreamStates(futInfo: Option[ReplState] = None,
                          optInfo: Option[ReplState] = None,
                          futTrade: Option[ReplState] = None,
                          optTrade: Option[ReplState] = None,
                          ordLog: Option[ReplState] = None) extends CaptureData

}

class MarketCapture(underlyingConnection: CGConnection, replication: ReplicationScheme,
                    repository: ReplicationStateRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository,
                    kestrel: KestrelConfig) extends Actor with FSM[CaptureState, CaptureData] {

  implicit def SecuritySemigroup: Semigroup[Security] = semigroup {
    case (s1, s2) => s2
  }

  import CaptureData._

  val Forts = Market("FORTS")

  implicit val revisionTracker = repository

  // Kestrel client
  val client = kestrel()

  // CGAte connection
  val connection = context.actorOf(Props(Connection(underlyingConnection)), "Connection")
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

  // Listeners
  val underlyingFutInfoListener = new CGListener(underlyingConnection, replication.futInfo(), new DataStreamSubscriber(FutInfoStream))
  val FutInfoListener = context.actorOf(Props(new Listener(underlyingFutInfoListener)), "FutInfoListener")

  val underlyingOptInfoListener = new CGListener(underlyingConnection, replication.optInfo(), new DataStreamSubscriber(OptInfoStream))
  val OptInfoListener = context.actorOf(Props(new Listener(underlyingOptInfoListener)), "OptInfoListener")

  val underlyingFutTradeListener = new CGListener(underlyingConnection, replication.futTrade(), new DataStreamSubscriber(FutTradeStream))
  val FutTradeListener = context.actorOf(Props(new Listener(underlyingFutTradeListener)), "FutTradeListener")

  val underlyingOptTradeListener = new CGListener(underlyingConnection, replication.optTrade(), new DataStreamSubscriber(OptTradeStream))
  val OptTradeListener = context.actorOf(Props(new Listener(underlyingOptTradeListener)), "OptTradeListener")

  val underlyingOrdLogListener = new CGListener(underlyingConnection, replication.ordLog(), new DataStreamSubscriber(OrdLogStream))
  val OrdLogListener = context.actorOf(Props(new Listener(underlyingOrdLogListener)), "OrdLogListener")

  val cgListeners = (FutInfoListener :: OptInfoListener :: FutTradeListener :: OptTradeListener :: OrdLogListener :: Nil)

  // Create captures
  implicit val ConvertOrder = new ConvertToMarketDb[OrdLog.orders_log, OrderPayload] {
    def apply(in: orders_log) = convertOrdersLog(in).getOrElse(throw new MarketCaptureException("Can't find isin for " + in))
  }

  implicit val ConvertFutureDeal = new ConvertToMarketDb[FutTrade.deal, TradePayload] {
    def apply(in: FutTrade.deal) = convertFuturesDeal(in).getOrElse(throw new MarketCaptureException("Can't find isin for " + in))
  }

  implicit val ConvertOptionDeal = new ConvertToMarketDb[OptTrade.deal, TradePayload] {
    def apply(in: OptTrade.deal) = convertOptionsDeal(in).getOrElse(throw new MarketCaptureException("Can't find isin for " + in))
  }

  lazy val ordersBuncher = new OrdersBuncher(client, kestrel.ordersQueue)
  lazy val futureDealsBuncher = new TradesBuncher(client, kestrel.tradesQueue)
  lazy val optionDealsBuncher = new TradesBuncher(client, kestrel.tradesQueue)
  val orderCapture = context.actorOf(Props(new MarketDbCapture[OrdLog.orders_log, OrderPayload](OrdLog.orders_log.TABLE_INDEX, OrdLogStream)(ordersBuncher)), "OrdersCapture")
  val futuresCapture = context.actorOf(Props(new MarketDbCapture[FutTrade.deal, TradePayload](FutTrade.deal.TABLE_INDEX, FutTradeStream)(futureDealsBuncher)), "FuturesCapture")
  val optionsCapture = context.actorOf(Props(new MarketDbCapture[OptTrade.deal, TradePayload](OptTrade.deal.TABLE_INDEX, OptTradeStream)(optionDealsBuncher)), "OptionsCapture")

  // Market Contents capture
  val marketContentsCapture = context.actorOf(Props(new MarketContentsCapture(FutInfoStream, OptInfoStream, repository)), "MarketContentsCapture")
  marketContentsCapture ! SubscribeMarketContents(self)

  // Supervisor
  override val supervisorStrategy = AllForOneStrategy() {
    case _: ComFailException => Stop
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

    case Event(FSM.StateTimeout, _) => stop(FSMFailure("Connecting MarketCapture timed out"))
  }

  when(CaptureState.InitializingMarketContents, stateTimeout = 15.second) {
    case Event(MarketContentsInitialized, _) => goto(CaptureState.Capturing)

    case Event(FSM.StateTimeout, _) => stop(FSMFailure("Initializing MarketCapture timed out"))
  }

  when(CaptureState.Capturing) {
    case Event(ShutDown, _) => goto(CaptureState.ShuttingDown) using StreamStates()
  }

  when(CaptureState.ShuttingDown) {
    case Event(DataStreamReplState(FutInfoStream, state), s: StreamStates) =>
      repository.setReplicationState(replication.futInfo.stream, state)
      handleStreamState(s.copy(futInfo = Some(ReplState(state))))

    case Event(DataStreamReplState(OptInfoStream, state), s: StreamStates) =>
      repository.setReplicationState(replication.optInfo.stream, state)
      handleStreamState(s.copy(optInfo = Some(ReplState(state))))

    case Event(DataStreamReplState(FutTradeStream, state), s: StreamStates) =>
      repository.setReplicationState(replication.futTrade.stream, state)
      handleStreamState(s.copy(futTrade = Some(ReplState(state))))

    case Event(DataStreamReplState(OptTradeStream, state), s: StreamStates) =>
      repository.setReplicationState(replication.optTrade.stream, state)
      handleStreamState(s.copy(optTrade = Some(ReplState(state))))

    case Event(DataStreamReplState(OrdLogStream, state), s: StreamStates) =>
      repository.setReplicationState(replication.ordLog.stream, state)
      handleStreamState(s.copy(ordLog = Some(ReplState(state))))
  }

  onTransition {
    case CaptureState.Idle -> CaptureState.Connecting =>
      log.info("Connecting Market capture, waiting for connection established")

    case CaptureState.Connecting -> CaptureState.InitializingMarketContents =>
      log.info("Initialize Market contents")
      connection ! StartMessageProcessing(100)
      FutInfoListener ! Listener.Open(ReplicationParams(Combined))
      OptInfoListener ! Listener.Open(ReplicationParams(Combined))

    case CaptureState.InitializingMarketContents -> CaptureState.Capturing =>
      log.info("Begin capturing Market data")
      log.debug("Market contents size = " + stateData.asInstanceOf[Contents].contents.size)

      val orderLogState = repository.replicationState(replication.ordLog.stream)
      val futTradeState = repository.replicationState(replication.futTrade.stream)
      val optTradeState = repository.replicationState(replication.optTrade.stream)

      log.info("Initial stream revisions; OrderLog = " + orderLogState + "; FutDeal = " + futTradeState + "; OptDeal = " + optTradeState)

      OrdLogListener ! Listener.Open(ReplicationParams(Combined, orderLogState.map(ReplState(_))))
      FutTradeListener ! Listener.Open(ReplicationParams(Combined, futTradeState.map(ReplState(_))))
      OptTradeListener ! Listener.Open(ReplicationParams(Combined, optTradeState.map(ReplState(_))))

    case CaptureState.Capturing -> CaptureState.ShuttingDown =>
      log.info("Shutting down Market Capture; Close all listeners!")
      listeners.foreach(_ ! Listener.Close)

  }

  whenUnhandled {
    case Event(Terminated(ref), _) if (ref == connection) => stop(FSMFailure("Connection terminated"))

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

  private def safeIsin(isinId: Int): Option[FullIsin] = {
    val cur = stateData match {
      case c: Contents => lastContents = Some(c); Some(c)
      case _ => None
    }
    (cur <+> lastContents).flatMap(_.contents.get(isinId).map(_.isin))
  }

  protected def handleStreamState(state: StreamStates): State = {
    (state.futInfo.<*****>(state.optInfo, state.futTrade, state.optTrade, state.ordLog)) {
      (_, _, _, _, _)
    } match {
      case states@Some((_, _, _, _, _)) =>
        log.debug("Streams shutted down in states = " + states)
        cgListeners.foreach(_ ! Listener.Dispose)
        connection ! Connection.Close
        connection ! Connection.Dispose
        stay()
      case _ =>
        log.debug("Waiting for all streams closed in state = " + state)
        stay() using state
    }
  }
}
