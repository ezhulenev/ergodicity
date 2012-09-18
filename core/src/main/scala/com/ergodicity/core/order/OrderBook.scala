package com.ergodicity.core.order

import akka.actor.{ActorLogging, Props, ActorRef, Actor}
import com.ergodicity.core.Security
import collection.mutable
import com.ergodicity.cgate.WhenUnhandled

class OrderBook(security: Security) extends Actor with ActorLogging with WhenUnhandled {

  protected[order] val orders = mutable.Map[Long, ActorRef]()

  override def preStart() {
    log.info("Start OrderBook for security = " + security)
  }

  protected def receive = handleCreateOrder orElse handleDeleteOrder orElse handleFillOrder orElse whenUnhandled

  private def handleCreateOrder: Receive = {
    case Create(order) =>
      log.debug("Create new order, id = {}", order.id)
      val orderActor = context.actorOf(Props(new OrderActor(order)), order.id.toString)
      orders(order.id) = orderActor
  }

  private def handleDeleteOrder: Receive = {
    case Delete(orderId, amount) =>
      log.debug("Cancel order, id = {}", orderId)
      orders(orderId) ! CancelOrder(amount)
  }

  private def handleFillOrder: Receive = {
    case Fill(orderId, deal, amount, price, rest) =>
      log.debug("Fill order, id = {}, amount = {}, rest = {}", orderId, amount, rest)
      orders(orderId) ! FillOrder(price, amount)
  }
}