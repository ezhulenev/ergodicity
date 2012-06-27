package com.ergodicity.core.order

import com.ergodicity.core.common.{IsinId, OrderDirection, OrderType}
import akka.actor.{FSM, Actor}
import akka.actor.FSM.Failure

sealed trait OrderState

object OrderState {

  case object Active extends OrderState

  case object Filled extends OrderState

  case object Cancelled extends OrderState

}

case class OrderProps(id: Long, sessionId: Int, isin: IsinId,
                      orderType: OrderType, direction: OrderDirection,
                      price: BigDecimal, amount: Int)

case class RestAmount(amount: Int) {
  if (amount < 0) throw new IllegalArgumentException("Rest amount should be greater then 0")
}

// Order Events
case class FillOrder(price: BigDecimal, amount: Int)

case class CancelOrder(amount: Int)

class Order(val order: OrderProps) extends Actor with FSM[OrderState, RestAmount] {

  import OrderState._

  log.debug("Create order: " + order)

  startWith(Active, RestAmount(order.amount))

  when(Active) {
    case Event(FillOrder(_, fillAmount), RestAmount(restAmount)) =>
      if (restAmount - fillAmount == 0) goto(Filled) using RestAmount(0)
      else stay() using RestAmount(restAmount - fillAmount)
  }

  when(Filled) {
    case Event(FillOrder(_, _), _) => stop(Failure("Order already Filled"))
  }

  when(Cancelled) {
    case _ => stop(Failure("Order already Cancelled"))
  }

  whenUnhandled {
    case Event(CancelOrder(cancelAmount), RestAmount(restAmount)) => goto(Cancelled) using RestAmount(restAmount - cancelAmount)
  }

  onTransition {
    case Active -> Filled => log.debug("Order filled")
    case Active -> Cancelled => log.debug("Order cancelled")
  }
}