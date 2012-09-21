package com.ergodicity.engine.service

import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor._
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.cgate._
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.cgate.config.Replies.RepliesParams
import com.ergodicity.cgate.config.{Replication, Replies}
import com.ergodicity.core.Market.Futures
import com.ergodicity.core.OrderType.ImmediateOrCancel
import com.ergodicity.core.SessionsTracking.{OngoingSessionTransition, OngoingSession, SubscribeOngoingSessions}
import com.ergodicity.core.broker.{Cancelled, OrderId, ReplySubscriber, Broker}
import com.ergodicity.core.broker.Protocol._
import com.ergodicity.core.order.OrdersTracking.{GetOrder, OrderRef, DropSession, GetSessionOrdersTracking}
import com.ergodicity.core.order.{Order, OrdersTracking}
import com.ergodicity.core.{Security, FutureContract, SessionId}
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.engine.service.Trading.{Sell, OrderExecution, Buy}
import com.ergodicity.engine.service.TradingService.SetOngoingSessionOrdersTracker
import com.ergodicity.engine.service.TradingState.TradingStates
import com.ergodicity.engine.underlying._
import com.ergodicity.engine.{Services, Engine}
import ru.micexrts.cgate.{Publisher => CGPublisher, Connection => CGConnection}
import scala.Some
import com.ergodicity.core.order.OrderActor.SubscribeOrderEvents
import com.ergodicity.engine.ReplicationScheme.{OptOrdersReplication, FutOrdersReplication}

object Trading {

  implicit case object Trading extends ServiceId

  case class Buy(security: Security, amount: Int, price: BigDecimal)

  case class Sell(security: Security, amount: Int, price: BigDecimal)

  class OrderExecution(val security: Security, val order: Order, orderActor: ActorRef)(broker: ActorRef) {
    implicit val cancelTimeout = Timeout(5.seconds)

    def cancel = security match {
      case _: FutureContract => (broker ? Broker.Cancel[Futures](OrderId(order.id))).mapTo[Cancelled]
      case _ => throw new RuntimeException("Unsupported security")
    }

    def subscribeOrderEvents(subscriber: ActorRef) {
      orderActor ! SubscribeOrderEvents(subscriber)
    }

    override def toString = "OrderExecution(security = " + security + ", order = " + order + ")"
  }

}


trait Trading {
  this: Services =>

  import Trading._

  def engine: Engine with UnderlyingListener with UnderlyingConnection with UnderlyingTradingConnections with UnderlyingPublisher with FutOrdersReplication with OptOrdersReplication

  lazy val creator = new TradingService(
    engine.listenerFactory, engine.publisherName, engine.brokerCode,
    engine.underlyingPublisher, engine.underlyingTradingConnection, engine.underlyingConnection,
    engine.futOrdersReplication, engine.optOrdersReplication
  )
  register(Props(creator), dependOn = InstrumentData.InstrumentData :: Nil)
}

protected[service] sealed trait TradingState

protected[service] object TradingState {

  case object Idle extends TradingState

  case object Starting extends TradingState

  case object Started extends TradingState

  case object Stopping extends TradingState

  case class TradingStates(broker: Option[State] = None, fut: Option[DataStreamState] = None, opt: Option[DataStreamState] = None)

}

protected[service] object TradingService {

  case class SetOngoingSessionOrdersTracker(sessionId: SessionId, ref: ActorRef)

}

protected[service] class TradingService(listener: ListenerFactory,
                                        publisherName: String,
                                        brokerCode: String,
                                        underlyingPublisher: CGPublisher,
                                        tradingConnection: CGConnection,
                                        replicationConnection: CGConnection,
                                        futOrdersReplication: Replication,
                                        optOrdersReplication: Replication)
                                       (implicit val services: Services, id: ServiceId) extends Actor with LoggingFSM[TradingState, TradingStates] with Service {

  import TradingState._
  import services._

  implicit val timeout = Timeout(30.second)

  private[this] val instrumentData = service(InstrumentData.InstrumentData)

  private[this] implicit val brokerConfig = Broker.Config(brokerCode)

  // Execution broker
  val TradingBroker = context.actorOf(Props(new Broker(underlyingPublisher)).withDispatcher(Engine.PublisherDispatcher), "Broker")

  private[this] val underlyingRepliesListener = listener(tradingConnection, Replies(publisherName)(), new ReplySubscriber(TradingBroker))
  private[this] val replyListener = context.actorOf(Props(new Listener(underlyingRepliesListener)).withDispatcher(Engine.ReplyDispatcher), "RepliesListener")

  // Orders tracking
  val FutOrdersStream = context.actorOf(Props(new DataStream), "FutOrdersStream")
  val OptOrdersStream = context.actorOf(Props(new DataStream), "OptOrdersStream")

  val OrdersTracking = context.actorOf(Props(new OrdersTracking(FutOrdersStream, OptOrdersStream)), "OrdersTracking")

  // Orders tracking listeners
  private[this] val underlyingFutListener = listener(replicationConnection, futOrdersReplication(), new DataStreamSubscriber(FutOrdersStream))
  private[this] val futListener = context.actorOf(Props(new Listener(underlyingFutListener)).withDispatcher(Engine.ReplicationDispatcher), "FutOrdersListener")

  private[this] val underlyingOptListener = listener(replicationConnection, optOrdersReplication(), new DataStreamSubscriber(OptOrdersStream))
  private[this] val optListener = context.actorOf(Props(new Listener(underlyingOptListener)).withDispatcher(Engine.ReplicationDispatcher), "OptOrdersListener")

  // Current session orders tracker
  var ordersTracker: ActorRef = context.system.deadLetters

  override def preStart() {
    log.info("Start " + id + " service")
    instrumentData ! SubscribeOngoingSessions(self)
  }

  startWith(Idle, TradingStates())

  when(Idle) {
    case Event(Start, _) =>
      log.info("Start " + id + " service")

      // Open broker publisher
      TradingBroker ! SubscribeTransitionCallBack(self)
      TradingBroker ! Broker.Open

      // Open orders tracking listeners
      futListener ! Listener.Open(ReplicationParams(Combined))
      optListener ! Listener.Open(ReplicationParams(Combined))

      // and subscribe for orders tracking stream states
      FutOrdersStream ! SubscribeTransitionCallBack(self)
      OptOrdersStream ! SubscribeTransitionCallBack(self)

      goto(Starting)
  }

  when(Starting, stateTimeout = 30.seconds) {
    case Event(CurrentState(FutOrdersStream, state: DataStreamState), states) => startUp(states.copy(fut = Some(state)))
    case Event(CurrentState(OptOrdersStream, state: DataStreamState), states) => startUp(states.copy(opt = Some(state)))

    case Event(Transition(FutOrdersStream, _, to: DataStreamState), states) => startUp(states.copy(fut = Some(to)))
    case Event(Transition(OptOrdersStream, _, to: DataStreamState), states) => startUp(states.copy(opt = Some(to)))

    case Event(CurrentState(TradingBroker, state: com.ergodicity.cgate.State), states) => startUp(states.copy(broker = Some(state)))
    case Event(Transition(TradingBroker, _, to: com.ergodicity.cgate.State), states) => startUp(states.copy(broker = Some(to)))

    case Event(FSM.StateTimeout, _) => failed("Starting timed out")
  }

  when(Started) {
    case Event(Stop, states) =>
      log.info("Stop " + id + " service")
      TradingBroker ! Broker.Close
      replyListener ! Listener.Close
      futListener ! Listener.Close
      optListener ! Listener.Close
      goto(Stopping)

    case Event(Buy(security@FutureContract(_, isin, _, _), amount, price), _) =>
      val orderId = (TradingBroker ? Broker.Buy[Futures](isin, amount, price, ImmediateOrCancel)).mapTo[OrderId]
      val orderRef = orderId flatMap (id => (ordersTracker ? GetOrder(id.id)).mapTo[OrderRef])
      orderRef map (order => new OrderExecution(security, order.order, order.ref)(TradingBroker)) pipeTo sender
      stay()

    case Event(Sell(security@FutureContract(_, isin, _, _), amount, price), _) =>
      val orderId = (TradingBroker ? Broker.Sell[Futures](isin, amount, price, ImmediateOrCancel)).mapTo[OrderId]
      val orderRef = orderId flatMap (id => (ordersTracker ? GetOrder(id.id)).mapTo[OrderRef])
      orderRef map (order => new OrderExecution(security, order.order, order.ref)(TradingBroker)) pipeTo sender
      stay()

  }

  when(Stopping, stateTimeout = 10.seconds) {
    case Event(Transition(TradingBroker, _, to: com.ergodicity.cgate.State), states) => shutDown(states.copy(broker = Some(to)))
    case Event(Transition(FutOrdersStream, _, to: DataStreamState), states) => shutDown(states.copy(fut = Some(to)))
    case Event(Transition(OptOrdersStream, _, to: DataStreamState), states) => shutDown(states.copy(opt = Some(to)))

    case Event(FSM.StateTimeout, _) => failed("Stopping timed out")
  }

  onTransition {
    case Starting -> Started =>
      // Open replies listener when publisher already started
      replyListener ! Listener.Open(RepliesParams)
      serviceStarted
  }

  whenUnhandled {
    case Event(OngoingSession(sessionId, ref), _) =>
      (OrdersTracking ? GetSessionOrdersTracking(sessionId.fut)).mapTo[ActorRef] map (SetOngoingSessionOrdersTracker(sessionId, _)) pipeTo self
      stay()

    case Event(OngoingSessionTransition(from, OngoingSession(sessionId, ref)), _) =>
      OrdersTracking ! DropSession(from.id.fut)
      (OrdersTracking ? GetSessionOrdersTracking(sessionId.fut)).mapTo[ActorRef] map (SetOngoingSessionOrdersTracker(sessionId, _)) pipeTo self
      stay()

    case Event(SetOngoingSessionOrdersTracker(sessionId, ref), _) =>
      log.debug("Set session orders tracker for " + sessionId + "; ref = " + ref)
      ordersTracker = ref
      stay()
  }

  private def shutDown(states: TradingStates) = states match {
    case TradingStates(Some(Closed), Some(DataStreamState.Closed), Some(DataStreamState.Closed)) =>
      // Dispose all underlying components
      TradingBroker ! Broker.Dispose
      replyListener ! Listener.Dispose
      futListener ! Listener.Dispose
      optListener ! Listener.Dispose
      serviceStopped
      stop(FSM.Shutdown)
    case _ => stay() using states
  }

  private def startUp(states: TradingStates) = states match {
    case TradingStates(Some(Active), Some(DataStreamState.Online), Some(DataStreamState.Online)) => goto(Started)
    case _ => stay() using states
  }

  initialize
}
