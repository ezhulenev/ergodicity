package com.ergodicity.engine.service

import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.{FSM, LoggingFSM, Actor, Props}
import akka.util.duration._
import com.ergodicity.cgate._
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.cgate.config.Replies.RepliesParams
import com.ergodicity.cgate.config.{Replication, Replies}
import com.ergodicity.core.broker.{ReplySubscriber, Broker}
import com.ergodicity.core.order.OrdersTracking
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.engine.service.TradingState.TradingStates
import com.ergodicity.engine.underlying._
import com.ergodicity.engine.{Services, Engine}
import java.io.File
import ru.micexrts.cgate.{Publisher => CGPublisher, Connection => CGConnection}

object Trading {

  implicit case object Trading extends ServiceId

}


trait Trading {
  this: Services =>

  import Trading._

  def engine: Engine with UnderlyingListener with UnderlyingConnection with UnderlyingTradingConnections with UnderlyingPublisher

  register(Props(
    new TradingService(engine.listenerFactory,
      engine.publisherName,
      engine.brokerCode,
      engine.underlyingPublisher,
      engine.underlyingRepliesConnection,
      engine.underlyingConnection)))
}

protected[service] sealed trait TradingState

protected[service] object TradingState {

  case object Idle extends TradingState

  case object Starting extends TradingState

  case object Started extends TradingState

  case object Stopping extends TradingState

  case class TradingStates(broker: Option[State] = None, fut: Option[DataStreamState] = None, opt: Option[DataStreamState] = None)

}

protected[service] class TradingService(listener: ListenerFactory,
                                        publisherName: String,
                                        brokerCode: String,
                                        underlyingPublisher: CGPublisher,
                                        underlyingRepliesConnection: CGConnection,
                                        replicationConnection: CGConnection)
                                       (implicit val services: Services, id: ServiceId) extends Actor with LoggingFSM[TradingState, TradingStates] with Service {

  import TradingState._
  import services._

  private[this] implicit val brokerConfig = Broker.Config(brokerCode)

  // Execution broker
  val TradingBroker = context.actorOf(Props(new Broker(underlyingPublisher)).withDispatcher(Engine.PublisherDispatcher), "Broker")

  private[this] val underlyingRepliesListener = listener(underlyingRepliesConnection, Replies(publisherName)(), new ReplySubscriber(TradingBroker))
  private[this] val replyListener = context.actorOf(Props(new Listener(underlyingRepliesListener)).withDispatcher(Engine.ReplyDispatcher), "RepliesListener")

  // Orders tracking
  val FutOrdersStream = context.actorOf(Props(new DataStream), "FutOrdersStream")
  val OptOrdersStream = context.actorOf(Props(new DataStream), "OptOrdersStream")

  val OrdersTracking = context.actorOf(Props(new OrdersTracking(FutOrdersStream, OptOrdersStream)))

  // Orders tracking listeners
  private[this] val futListenerConfig = Replication("FORTS_FUTTRADE_REPL", new File("cgate/scheme/FutOrders.ini"), "CustReplScheme")
  private[this] val underlyingFutListener = listener(replicationConnection, futListenerConfig(), new DataStreamSubscriber(FutOrdersStream))
  private[this] val futListener = context.actorOf(Props(new Listener(underlyingFutListener)).withDispatcher(Engine.ReplicationDispatcher), "FutOrdersListener")

  private[this] val optListenerConfig = Replication("FORTS_OPTTRADE_REPL", new File("cgate/scheme/OptOrders.ini"), "CustReplScheme")
  private[this] val underlyingOptListener = listener(replicationConnection, optListenerConfig(), new DataStreamSubscriber(OptOrdersStream))
  private[this] val optListener = context.actorOf(Props(new Listener(underlyingOptListener)).withDispatcher(Engine.ReplicationDispatcher), "OptOrdersListener")

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

  when(Starting, stateTimeout = 10.seconds) {
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
