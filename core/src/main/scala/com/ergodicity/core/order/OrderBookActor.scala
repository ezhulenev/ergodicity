package com.ergodicity.core.order

import akka.actor._
import com.ergodicity.core.Security
import collection.mutable
import com.ergodicity.cgate.WhenUnhandled
import akka.actor.FSM._
import com.ergodicity.core.order.OrderState.{Active, Cancelled, Filled}
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack

class OrderBookActor(security: Security) extends Actor with ActorLogging with WhenUnhandled {

  protected[order] val orders = mutable.Map[Long, ActorRef]()
  protected[order] val orderRefs = mutable.Map[ActorRef, Long]()


  override def preStart() {
    log.info("Start OrderBook for security = " + security)
  }


  protected def receive = handleCreateOrder orElse handleDeleteOrder orElse handleFillOrder orElse handleOrderTransitions orElse whenUnhandled

  private def handleCreateOrder: Receive = {
    case OrderAction(orderId, Create(order)) if (order.noSystem == false) =>
      log.debug("Create new order, id = {}, noSystem = {}", orderId, order.noSystem)
      val orderActor = context.actorOf(Props(new OrderActor(order)), orderId.toString)
      orders(order.id) = orderActor
      orderRefs(orderActor) = order.id
      orderActor ! SubscribeTransitionCallBack(self)
  }

  private def handleDeleteOrder: Receive = {
    case OrderAction(orderId, cancel: Cancel) if (orders contains orderId) =>
      log.debug("Cancel order, id = {}", orderId)
      orders(orderId) ! cancel
  }

  private def handleFillOrder: Receive = {
    case OrderAction(orderId, fill@Fill(amount, rest, deal)) if (orders contains orderId) =>
      log.debug("Fill order, id = {}, amount = {}, rest = {}, deal = {}", orderId, amount, rest, deal)
      orders(orderId) ! fill
  }

  private def handleOrderTransitions: Receive = {
    case CurrentState(ref, state: OrderState) if (orderRefs contains ref) =>
      if (completed(state)) kill(ref)

    case Transition(ref, _, state: OrderState) if (orderRefs contains ref) =>
      if (completed(state)) kill(ref)
  }

  private def kill(ref: ActorRef) {
    ref ! PoisonPill
    val orderId = orderRefs.remove(ref).get
    orders.remove(orderId)
  }

  private def completed(state: OrderState) = state match {
    case Active => false
    case Filled => true
    case Cancelled => true
  }

}