package com.ergodicity.core.order

import akka.actor._
import akka.util
import akka.util.duration._
import collection.mutable
import com.ergodicity.cgate.DataStream._
import com.ergodicity.cgate.StreamEvent.ClearDeleted
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.StreamEvent._
import com.ergodicity.cgate.scheme.{FutOrder, OptOrder}
import com.ergodicity.cgate.{Protocol, WhenUnhandled, Reads}
import com.ergodicity.core.order.OrdersTracking.{GetOrder, OrderRef, StickyAction, IllegalEvent}


object OrdersTracking {

  case class GetSessionOrdersTracking(sessionId: Int)

  case class GetOrder(id: Long)

  case class OrderRef(order: Order, ref: ActorRef)

  case class DropSession(sessionId: Int)

  case class IllegalEvent(event: Any) extends IllegalArgumentException

  case class StickyAction(sessionId: Int, action: Action)

}

class OrdersTracking(FutTradeStream: ActorRef, OptTradeStream: ActorRef) extends Actor with ActorLogging with WhenUnhandled {

  import OrdersTracking._

  implicit val timeout = util.Timeout(1.second)

  val sessions = mutable.Map[Int, ActorRef]()

  // Dispatch Futures and Options orders
  val futuresDispatcher = context.actorOf(Props(new FutureOrdersDispatcher(self, FutTradeStream)(Protocol.ReadsFutOrders)), "FutureOrdersDispatcher")
  val optionsDispatcher = context.actorOf(Props(new OptionOrdersDispatcher(self, OptTradeStream)(Protocol.ReadsOptOrders)), "OptionOrdersDispatcher")

  protected def receive = trackingHandler orElse whenUnhandled

  private def trackingHandler: Receive = {
    case StickyAction(sessionId, action) =>
      lazy val actor = context.actorOf(Props(new SessionOrdersTracking(sessionId)), sessionId.toString)
      sessions.getOrElseUpdate(sessionId, actor) ! action

    case GetSessionOrdersTracking(sessionId) =>
      lazy val actor = context.actorOf(Props(new SessionOrdersTracking(sessionId)), sessionId.toString)
      sender ! sessions.getOrElseUpdate(sessionId, actor)

    case DropSession(sessionId) if (sessions.contains(sessionId)) =>
      log.info("Drop session: " + sessionId)
      sessions.remove(sessionId) foreach (_ ! Kill)
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
      ordersTracking ! StickyAction(record.get_sess_id(), Action(record))
  }
}

class OptionOrdersDispatcher(ordersTracking: ActorRef, stream: ActorRef)(implicit val read: Reads[OptOrder.orders_log]) extends OrdersDispatcher(ordersTracking, stream) {
  def dispatch = {
    case e@StreamData(OptOrder.orders_log.TABLE_INDEX, data) =>
      val record = read(data)
      if (record.get_replAct() != 0) {
        throw new IllegalEvent(e)
      }
      ordersTracking ! StickyAction(record.get_sess_id(), Action(record))

  }
}


class SessionOrdersTracking(sessionId: Int) extends Actor with ActorLogging with WhenUnhandled {
  protected[order] val orders = mutable.Map[Long, (Order, ActorRef)]()

  private[this] val pendingOrders = mutable.Map[Long, ActorRef]()

  protected def receive = getOrder orElse handleCreateOrder orElse handleDeleteOrder orElse handleFillOrder orElse whenUnhandled

  private def getOrder: Receive = {
    case GetOrder(id) =>
      if (orders contains id)
        sender ! OrderRef(orders(id)._1, orders(id)._2)
      else
        pendingOrders(id) = sender
  }

  private def handleCreateOrder: Receive = {
    case Create(order) =>
      log.debug("Create new order, id = " + order.id)
      val orderActor = context.actorOf(Props(new OrderActor(order)), order.id.toString)
      orders(order.id) = (order, orderActor)
      pendingOrders.get(order.id) map (_ ! OrderRef(order, orderActor))
      pendingOrders.remove(order.id)
  }

  private def handleDeleteOrder: Receive = {
    case Delete(orderId, amount) =>
      log.debug("Cancel order, id = " + orderId)
      orders(orderId)._2 ! CancelOrder(amount)
  }

  private def handleFillOrder: Receive = {
    case Fill(orderId, amount, rest, deal) =>
      log.debug("Fill order, id = {}, amount = {}, rest = {}, deal = {}", orderId, amount, rest, deal)
      orders(orderId)._2 ! FillOrder(amount, deal)
  }
}