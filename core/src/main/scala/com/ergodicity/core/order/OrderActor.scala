package com.ergodicity.core.order

import com.ergodicity.core.{IsinId, OrderDirection, OrderType}
import akka.actor.{FSM, Actor}
import com.ergodicity.core.order.OrderActor.IllegalLifeCycleEvent

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

object OrderActor {
  case class IllegalLifeCycleEvent(msg: String, event: Any) extends IllegalArgumentException
}

class OrderActor(val order: OrderProps) extends Actor with FSM[OrderState, RestAmount] {

  import OrderState._

  log.debug("Create futOrder: " + order)

  startWith(Active, RestAmount(order.amount))

  when(Active) {
    case Event(FillOrder(_, fillAmount), RestAmount(restAmount)) =>
      if (restAmount - fillAmount == 0) goto(Filled) using RestAmount(0)
      else stay() using RestAmount(restAmount - fillAmount)
  }

  when(Filled) {
    case Event(e@FillOrder(_, _), _) => throw new IllegalLifeCycleEvent("Order already Filled", e)
  }

  when(Cancelled) {
    case e => throw new IllegalLifeCycleEvent("Order already Cancelled", e)
  }

  whenUnhandled {
    case Event(CancelOrder(cancelAmount), RestAmount(restAmount)) => goto(Cancelled) using RestAmount(restAmount - cancelAmount)
  }

  onTransition {
    case Active -> Filled => log.debug("Order filled")
    case Active -> Cancelled => log.debug("Order cancelled")
  }
}