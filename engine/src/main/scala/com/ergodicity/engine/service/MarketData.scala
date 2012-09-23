package com.ergodicity.engine.service

import com.ergodicity.engine.{Engine, Services}
import com.ergodicity.engine.underlying.{ListenerFactory, UnderlyingListener, UnderlyingConnection}
import com.ergodicity.engine.ReplicationScheme._
import akka.actor.{FSM, Props, LoggingFSM, Actor}
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.duration._
import com.ergodicity.cgate.{DataStreamState, DataStreamSubscriber, DataStream, Listener}
import com.ergodicity.cgate.config.Replication
import ru.micexrts.cgate.{Connection => CGConnection}
import akka.util.Timeout
import com.ergodicity.core.order.{OrdersSnapshotActor, OrderBooksTracking}
import com.ergodicity.core.SessionsTracking.{OngoingSessionTransition, OngoingSession, SubscribeOngoingSessions}
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.cgate.config.Replication.{ReplicationMode, ReplicationParams}
import com.ergodicity.cgate.config.Replication.ReplicationMode.Snapshot
import com.ergodicity.core.order.OrdersSnapshotActor.{OrdersSnapshot, GetOrdersSnapshot}
import com.ergodicity.core.order.OrderBooksTracking.Snapshots
import com.ergodicity.core.session.SessionActor.{AssignedContents, GetAssignedContents}
import akka.actor.FSM.{UnsubscribeTransitionCallBack, Transition, CurrentState, SubscribeTransitionCallBack}

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

  case object AssigningInstruments extends MarketDataState

  case object WaitingSnapshots extends MarketDataState

  case object StartingOrderBooks extends MarketDataState

  case object Started extends MarketDataState

  case object Stopping extends MarketDataState

}

protected[service] class MarketDataService(listener: ListenerFactory, underlyingConnection: CGConnection,
                                           futOrderBookReplication: Replication, optOrderBookReplication: Replication, ordLogReplication: Replication)
                                          (implicit val services: Services, id: ServiceId) extends Actor with LoggingFSM[MarketDataState, Unit] with Service {

  import MarketDataState._

  implicit val timeout = Timeout(30.second)

  private[this] val instrumentData = services(InstrumentData.InstrumentData)

  // Orders books
  val OrdLogStream = context.actorOf(Props(new DataStream), "OrdLogStream")

  val OrderBooks = context.actorOf(Props(new OrderBooksTracking(OrdLogStream)), "OrderBooks")

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
  }

  startWith(Idle, ())

  when(Idle) {
    case Event(Start, _) =>
      instrumentData ! SubscribeOngoingSessions(self)
      goto(AssigningInstruments)
  }

  when(AssigningInstruments, stateTimeout = 30.seconds) {
    case Event(OngoingSession(_, ref), _) =>
      (ref ? GetAssignedContents).mapTo[AssignedContents] pipeTo self
      stay()

    case Event(assigned: AssignedContents, _) =>
      // Forward assigned contents to OrderBooks
      OrderBooks ! assigned

      // Open orderbook snapshots
      futOrderBookListener ! Listener.Open(ReplicationParams(Snapshot))
      optOrderBookListener ! Listener.Open(ReplicationParams(Snapshot))

      // Get Futures & Options snapshots
      val futSnapshot = (FuturesSnapshot ? GetOrdersSnapshot).mapTo[OrdersSnapshot]
      val optSnapshot = (OptionsSnapshot ? GetOrdersSnapshot).mapTo[OrdersSnapshot]
      (futSnapshot zip optSnapshot).map(tuple => Snapshots(tuple._1, tuple._2)) pipeTo self

      goto(WaitingSnapshots)

    case Event(FSM.StateTimeout, _) => failed("Assigning instruments timed out")
  }

  when(WaitingSnapshots, stateTimeout = 30.seconds) {
    case Event(snapshots@Snapshots(fut, opt), _) =>
      OrderBooks ! snapshots

      // Open orders log from min revision
      val params = ReplicationParams(ReplicationMode.Combined, Map("orders_log" -> scala.math.min(fut.revision, opt.revision)))
      ordLogListener ! Listener.Open(params)

      // And watch for stream state
      OrdLogStream ! SubscribeTransitionCallBack(self)
      goto(StartingOrderBooks)

    case Event(FSM.StateTimeout, _) => failed("Waiting snapshots timed out")
  }

  when(StartingOrderBooks, stateTimeout = 30.seconds) {
    case Event(CurrentState(OrdLogStream, DataStreamState.Online), _) => goto(Started)

    case Event(Transition(OrdLogStream, _, DataStreamState.Online), _) => goto(Started)

    case Event(FSM.StateTimeout, _) => failed("Starting timed out")
  }

  when(Started) {
    case Event(Stop, states) =>
      log.info("Stop " + id + " service")
      ordLogListener ! Listener.Close
      goto(Stopping)
  }

  when(Stopping, stateTimeout = 10.seconds) {
    case Event(Transition(OrdLogStream, _, DataStreamState.Closed), _) => shutDown

    case Event(FSM.StateTimeout, _) => failed("Stopping timed out")
  }

  onTransition {
    case StartingOrderBooks -> Started =>
      OrdLogStream ! UnsubscribeTransitionCallBack(self)
      services.serviceStarted
  }

  whenUnhandled {
    case Event(OngoingSessionTransition(_, OngoingSession(_, ref)), _) =>
      (ref ? GetAssignedContents).mapTo[AssignedContents] pipeTo OrderBooks
      stay()
  }

  initialize

  private def shutDown: State = {
    ordLogListener ! Listener.Dispose
    futOrderBookListener ! Listener.Dispose
    optOrderBookListener ! Listener.Dispose
    services.serviceStopped
    stop(FSM.Shutdown)
  }
}