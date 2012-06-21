package com.ergodicity.core.order

import com.ergodicity.core.common.IsinId
import akka.actor.Actor
import akka.event.Logging

class OrderBook(isin: IsinId) extends Actor {
  val log = Logging(context.system, self)

  protected def receive = null
}