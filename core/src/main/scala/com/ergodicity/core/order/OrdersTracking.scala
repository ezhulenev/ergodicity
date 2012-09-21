package com.ergodicity.core.order

import akka.actor._
import akka.util
import akka.util.duration._
import collection.mutable
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.StreamEvent._
import com.ergodicity.cgate.scheme.{FutOrder, OptOrder}
import com.ergodicity.cgate.{Protocol, WhenUnhandled, Reads}
import com.ergodicity.core.order.OrdersTracking._
import com.ergodicity.core.SessionsTracking.{OngoingSessionTransition, OngoingSession}
import com.ergodicity.core.SessionId

class OrdersTrackingException(message: String) extends RuntimeException(message)

sealed trait OrdersTrackingState

object OrdersTrackingState {

  case object CatchingOngoingSession extends OrdersTrackingState

  case object Tracking extends OrdersTrackingState

}

object OrdersTracking {

  case class GetOrder(id: Long)

  case class OrderRef(order: Order, ref: ActorRef)

  case class IllegalEvent(event: Any) extends IllegalArgumentException

  case class OrderLog(sessionId: Int, action: OrderAction)

}

class OrdersTracking(FutTradeStream: ActorRef, OptTradeStream: ActorRef) extends Actor with LoggingFSM[OrdersTrackingState, Option[OngoingSession]] {

  import OrdersTrackingState._
  import OrdersTracking._

  implicit val timeout = util.Timeout(1.second)

  // Dispatch Futures and Options orders
  val futuresDispatcher = context.actorOf(Props(new FutureOrdersDispatcher(self, FutTradeStream)(Protocol.ReadsFutOrders)), "FutureOrdersDispatcher")
  val optionsDispatcher = context.actorOf(Props(new OptionOrdersDispatcher(self, OptTradeStream)(Protocol.ReadsOptOrders)), "OptionOrdersDispatcher")

  protected[order] val orders = mutable.Map[Long, (Order, ActorRef)]()
  private[this] val pendingOrders = mutable.Map[Long, ActorRef]()

  startWith(CatchingOngoingSession, None)

  when(CatchingOngoingSession, stateTimeout = 40.seconds) {
    case Event(session: OngoingSession, None) => goto(Tracking) using Some(session)

    case Event(FSM.StateTimeout, None) => throw new OrdersTrackingException("Time out with receiving OngoingSession")
  }

  when(Tracking) {
    case Event(OngoingSessionTransition(from, to), _) =>
      log.info("Drop all orders from previous session = " + from)
      log.info("Switch to new session = " + to)
      // Remove all previous orders
      orders.values foreach (tuple => tuple._2 ! PoisonPill)
      orders.clear()
      pendingOrders.clear()
      stay() using Some(to)

    case Event(OrderLog(sessionId, action), Some(OngoingSession(SessionId(fut, opt), _))) if (sessionId == fut || sessionId == opt) =>
      act(action)
      stay()
  }

  whenUnhandled {
    case Event(GetOrder(id), _) =>
      if (orders contains id)
        sender ! OrderRef(orders(id)._1, orders(id)._2)
      else
        pendingOrders(id) = sender
      stay()
  }

  initialize

  private def act(action: OrderAction) {
    action match {
      case OrderAction(orderId, Create(order)) =>
        log.debug("Create new order, id = " + order.id)
        val orderActor = context.actorOf(Props(new OrderActor(order)), order.id.toString)
        orders(order.id) = (order, orderActor)
        pendingOrders.get(order.id) foreach (_ ! OrderRef(order, orderActor))
        pendingOrders.remove(order.id)

      case OrderAction(orderId, cancel@Cancel(amount)) =>
        log.debug("Cancel order, id = " + orderId)
        orders(orderId)._2 ! cancel

      case OrderAction(orderId, fill@Fill(amount, rest, deal)) =>
        log.debug("Fill order, id = {}, amount = {}, rest = {}, deal = {}", orderId, amount, rest, deal)
        orders(orderId)._2 ! fill
    }
  }
}

abstract class OrdersDispatcher(ordersTracking: ActorRef, stream: ActorRef) extends Actor with ActorLogging with WhenUnhandled {

  override def preStart() {
    stream ! SubscribeStreamEvents(self)
  }

  def dispatch: Receive

  def defaultDispatcher: Receive = {
    case TnBegin =>

    case TnCommit =>

    case e@ClearDeleted(_, _) =>
  }

  protected def receive = dispatch orElse defaultDispatcher orElse whenUnhandled

}

class FutureOrdersDispatcher(ordersTracking: ActorRef, stream: ActorRef)(implicit val read: Reads[FutOrder.orders_log]) extends OrdersDispatcher(ordersTracking, stream) {
  def dispatch = {
    case e@StreamData(FutOrder.orders_log.TABLE_INDEX, data) =>
      val record = read(data)
      if (record.get_replAct() != 0) {
        throw new IllegalEvent(e)
      }
      ordersTracking ! OrderLog(record.get_sess_id(), OrderAction(record.get_id_ord(), Action(record)))
  }
}

class OptionOrdersDispatcher(ordersTracking: ActorRef, stream: ActorRef)(implicit val read: Reads[OptOrder.orders_log]) extends OrdersDispatcher(ordersTracking, stream) {
  def dispatch = {
    case e@StreamData(OptOrder.orders_log.TABLE_INDEX, data) =>
      val record = read(data)
      if (record.get_replAct() != 0) {
        throw new IllegalEvent(e)
      }
      ordersTracking ! OrderLog(record.get_sess_id(), OrderAction(record.get_id_ord(), Action(record)))
  }
}