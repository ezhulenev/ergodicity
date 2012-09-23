package com.ergodicity.core.order

import akka.actor.{ActorLogging, Props, ActorRef, Actor}
import com.ergodicity.core.Security
import collection.mutable
import com.ergodicity.cgate.WhenUnhandled

class OrderBookActor(security: Security) extends Actor with ActorLogging with WhenUnhandled {

  protected[order] val orders = mutable.Map[Long, ActorRef]()

  override def preStart() {
    log.info("Start OrderBook for security = " + security)
  }


  protected def receive = handleCreateOrder orElse handleDeleteOrder orElse handleFillOrder orElse whenUnhandled

  private def handleCreateOrder: Receive = {
    case OrderAction(orderId, Create(order)) =>
      log.debug("Create new order, id = {}, noSystem = {}", orderId, order.noSystem)
      val orderActor = context.actorOf(Props(new OrderActor(order)), orderId.toString)
      orders(order.id) = orderActor
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
}