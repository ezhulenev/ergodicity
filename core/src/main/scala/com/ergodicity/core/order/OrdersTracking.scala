package com.ergodicity.core.order

import akka.actor._
import akka.util.duration._
import akka.pattern.ask
import com.ergodicity.cgate.DataStream._
import com.ergodicity.cgate.Protocol.ReadsFutTradeOrders
import com.ergodicity.cgate.StreamEvent._
import com.ergodicity.cgate.scheme.{FutInfo, OptTrade, FutTrade}
import akka.dispatch.Await
import com.ergodicity.cgate.{WhenUnhandled, DataStreamState, Reads}
import akka.actor.FSM._
import collection.mutable
import akka.util
import com.ergodicity.core.order.OrdersTrackingData.StreamStates
import com.ergodicity.core.order.OrdersTrackingState.StreamStates
import com.ergodicity.cgate.DataStream.BindingSucceed
import com.ergodicity.core.order.FillOrder
import akka.actor.FSM.Transition
import com.ergodicity.core.order.OrdersTrackingState.StreamStates
import akka.actor.FSM.CurrentState
import akka.actor.FSM.UnsubscribeTransitionCallBack
import com.ergodicity.core.order.TrackSession
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.core.order.CancelOrder
import com.ergodicity.cgate.StreamEvent.ClearDeleted
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.core.order.DropSession
import com.ergodicity.cgate.DataStream.BindTable
import com.ergodicity.core.order.OrdersTracking.IllegalEvent


private[order] sealed trait OrdersTrackingState

private[order] object OrdersTrackingState {

  case object Binded extends OrdersTrackingState

  case object Online extends OrdersTrackingState

  case class StreamStates(fut: Option[DataStreamState] = None, opt: Option[DataStreamState] = None)

}

object OrdersTracking {

  case class IllegalEvent(event: Any) extends IllegalArgumentException

  case class SessionSticked

}

class OrdersTracking(FutTradeStream: ActorRef, OptTradeStream: ActorRef) extends Actor with FSM[OrdersTrackingState, StreamStates] {

  import OrdersTracking._
  import OrdersTrackingState._

  implicit val timeout = util.Timeout(1.second)

  val readFuture = implicitly[Reads[FutTrade.orders_log]]
  val readOption = implicitly[Reads[OptTrade.orders_log]]

  val sessions = mutable.Map[Int, ActorRef]()

  log.debug("Bind to FutTrade & OptTrade data streams")

  // Bind to tables
  val futuresBinding = (FutTradeStream ? BindTable(FutTrade.orders_log.TABLE_INDEX, self)).mapTo[BindingResult]
  val optionsBinding = (OptTradeStream ? BindTable(OptTrade.orders_log.TABLE_INDEX, self)).mapTo[BindingResult]

  Await.result(futuresBinding zip optionsBinding, 1.second) match {
    case (_: BindingSucceed, _: BindingSucceed) => log.info("Successfully binded to FutTrade & OptTrade streams")
    case failure => throw new IllegalStateException("Failed bind to FutTrade & OptTrade streams, failure = " + failure)
  }

  // Track Data Stream state
  FutTradeStream ! SubscribeTransitionCallBack(self)
  OptTradeStream ! SubscribeTransitionCallBack(self)

  startWith(Binded, StreamStates())

  when(Binded)(handleDataStreamEvents orElse trackSession orElse dropSession orElse {
    case Event(CurrentState(FutTradeStream, DataStreamState.Online), _) => goto(Online)
    case Event(Transition(FutTradeStream, _, DataStreamState.Online), _) => goto(Online)

  })

  when(Online) {
    handleDataStreamEvents orElse trackSession orElse dropSession
  }

  onTransition {
    case Binded -> Online => FutTradeStream ! UnsubscribeTransitionCallBack(self)
  }

  private def trackSession: StateFunction = {
    case Event(TrackSession(sessionId), sessions) if (sessions.contains(sessionId)) =>
      sender ! sessions(sessionId)
      stay()

    case Event(TrackSession(sessionId), sessions) if (!sessions.contains(sessionId)) =>
      val actor = context.actorOf(Props(new SessionOrdersTracking(sessionId)), "Session-" + sessionId)
      sender ! actor
      stay() using (sessions + (sessionId -> actor))
  }

  private def dropSession: StateFunction = {
    case Event(DropSession(sessionId), sessions) if (sessions.contains(sessionId)) =>
      log.info("Drop session: " + sessionId)
      sessions(sessionId) ! Kill
      stay() using (sessions - sessionId)
  }
}

class OrdersDispatcher[T <: FutTrade.orders_log](sessions: mutable.Map[Int, ActorRef])(implicit val read: Reads[T]) extends Actor with ActorLogging with WhenUnhandled {
  protected def receive = handleDataStreamEvents orElse whenUnhandled

  private def handleDataStreamEvents: Receive = {
    case TnBegin =>

    case TnCommit =>

    case e@ClearDeleted(_, _) =>

    case e@StreamData(FutTrade.orders_log.TABLE_INDEX, data) if (read(data).get_replAct() != 0) => throw new IllegalEvent(e)

    case StreamData(FutTrade.orders_log.TABLE_INDEX, data) if (sessions contains (read(data).get_sess_id())) =>
      val record = read(data)
      sessions(record.get_sess_id()) ! record

    case StreamData(FutTrade.orders_log.TABLE_INDEX, data) if (!sessions.contains(read(data).get_sess_id())) =>
      val record = read(data)
      val sessionId = record.get_sess_id()
      log.debug("Create session orders for: " + sessionId)
      val actor = context.actorOf(Props(new SessionOrdersTracking(sessionId)), "Session-" + sessionId)
      sessions(sessionId) = actor
      actor ! record
  }

}

class SessionOrdersTracking(sessionId: Int) extends Actor with ActorLogging with WhenUnhandled {
  protected[order] val orders = mutable.Map[Long, ActorRef]()

  protected def receive = handleCreateOrder orElse handleDeleteOrder orElse handleFillOrder orElse whenUnhandled

  private def handleCreateOrder: Receive = {
    case record: FutTrade.orders_log if (Action(record) == Create && !orderExists(record) && matchSession(record)) =>
      val orderId = record.get_id_ord()
      log.debug("Create new futOrder, id = " + orderId)
      val order = context.actorOf(Props(new Order(record)), "Order-" + orderId)
      orders(orderId) = order
  }

  private def handleDeleteOrder: Receive = {
    case record: FutTrade.orders_log if (Action(record) == Delete && orderExists(record) && matchSession(record)) =>
      val orderId = record.get_id_ord()
      log.debug("Cancel futOrder, id = " + orderId)
      orders(orderId) ! CancelOrder(record.amount)
  }

  private def handleFillOrder: Receive = {
    case record: FutTrade.orders_log if (Action(record) == Fill && orderExists(record) && matchSession(record)) =>
      val orderId = record.get_id_ord()
      log.debug("Fill futOrder, id = " + orderId + ", amount = " + record.amount + ", rest = " + record.get_amount_rest() + ", deal id = " + record.get_id_deal())
      orders(orderId) ! FillOrder(record.price, record.amount)
  }

  private def matchSession(record: FutTrade.orders_log) = record.get_sess_id() == sessionId

  private def orderExists(record: FutTrade.orders_log) = orders.contains(record.get_id_ord())
}