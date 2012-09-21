package com.ergodicity.engine.service

import com.ergodicity.engine.{Engine, Services}
import com.ergodicity.engine.underlying.{ListenerFactory, UnderlyingListener, UnderlyingConnection}
import com.ergodicity.engine.ReplicationScheme._
import akka.actor.{Props, LoggingFSM, Actor}
import akka.util.duration._
import com.ergodicity.cgate.{DataStreamSubscriber, DataStream, Listener, DataStreamState}
import com.ergodicity.cgate.config.Replication
import ru.micexrts.cgate.{Connection => CGConnection}
import com.ergodicity.engine.service.MarketDataState.StreamStates
import akka.util.Timeout
import com.ergodicity.core.order.{OrdersSnapshotActor, OrderBooksTracking}
import com.ergodicity.core.SessionsTracking.SubscribeOngoingSessions

object MarketData {

  implicit case object MarketData extends ServiceId

}

trait MarketData {
  this: Services =>

  import MarketData._

  def engine: Engine with UnderlyingConnection with UnderlyingListener with OrdLogReplication with FutOrderBookReplication with OptOrderBookReplication

  lazy val creator = new MarketDataService(engine.listenerFactory, engine.underlyingConnection, engine.futOrderbookReplication, engine.optOrderbookReplication, engine.ordLogReplication)
  register(Props(creator), dependOn = InstrumentData.InstrumentData :: Nil)
}

protected[service] sealed trait MarketDataState

object MarketDataState {

  case object Idle extends MarketDataState

  case object Starting extends MarketDataState

  case object Started extends MarketDataState

  case object Stopping extends MarketDataState

  case class StreamStates(fut: Option[DataStreamState] = None, opt: Option[DataStreamState] = None)

}

protected[service] class MarketDataService(listener: ListenerFactory, underlyingConnection: CGConnection,
                                           futOrderBookReplication: Replication, optOrderBookReplication: Replication, ordLogReplication: Replication)
                                          (implicit val services: Services, id: ServiceId) extends Actor with LoggingFSM[InstrumentDataState, StreamStates] with Service {

  implicit val timeout = Timeout(30.second)

  private[this] val instrumentData = services(InstrumentData.InstrumentData)

  // Orders books
  val OrdLogStream = context.actorOf(Props(new DataStream), "OrdLogStream")

  val OrderBooks = context.actorOf(Props(new OrderBooksTracking(OrdLogStream)), "OrdLogStream")

  // Order Log listener
  private[this] val underlyingOrdLogListener = listener(underlyingConnection, ordLogReplication(), new DataStreamSubscriber(OrdLogStream))
  private[this] val ordLogListener = context.actorOf(Props(new Listener(underlyingOrdLogListener)).withDispatcher(Engine.ReplicationDispatcher), "OrdLogListener")

  // OrderBook streams
  val FutOrderBookStream = context.actorOf(Props(new DataStream), "FutOrderBookStream")
  val OptOrderBookStream = context.actorOf(Props(new DataStream), "OptOrderBookStream")

  // OrderBook listeners
  private[this] val underlyingFutOrderBookListener = listener(underlyingConnection, futOrderBookReplication(), new DataStreamSubscriber(FutOrderBookStream))
  private[this] val futOrderBookListener = context.actorOf(Props(new Listener(underlyingFutOrderBookListener)).withDispatcher(Engine.ReplicationDispatcher), "FutOrderBookListener")

  private[this] val underlyingOptOrderBookListener = listener(underlyingConnection, optOrderBookReplication(), new DataStreamSubscriber(OptOrderBookStream))
  private[this] val optOrderBookListener = context.actorOf(Props(new Listener(underlyingOptOrderBookListener)).withDispatcher(Engine.ReplicationDispatcher), "OptOrderBookListener")

  // OrderBook snapshots
  val FuturesSnapshot = context.actorOf(Props(new OrdersSnapshotActor(FutOrderBookStream)), "FuturesSnapshot")
  val OptionsSnapshot = context.actorOf(Props(new OrdersSnapshotActor(OptOrderBookStream)), "OptionsSnapshot")

  override def preStart() {
    log.info("Start " + id + " service")
    instrumentData ! SubscribeOngoingSessions(self)
  }

}