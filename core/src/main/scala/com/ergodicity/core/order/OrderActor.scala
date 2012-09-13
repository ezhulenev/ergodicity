package com.ergodicity.core.order

import akka.actor.{FSM, Actor}
import com.ergodicity.core.order.OrderActor.IllegalLifeCycleEvent

sealed trait OrderState

object OrderState {

  case object Active extends OrderState

  case object Filled extends OrderState

  case object Cancelled extends OrderState

}

case class Trace(rest: Int, actions: Seq[OrderAction] = Seq()) {
  if (rest < 0) throw new IllegalArgumentException("Rest amount should be greater then 0")
}

// Order Actions

sealed trait OrderAction

case class FillOrder(price: BigDecimal, amount: Int) extends OrderAction

case class CancelOrder(amount: Int) extends OrderAction

object OrderActor {

  case class IllegalLifeCycleEvent(msg: String, event: Any) extends IllegalArgumentException

}

class OrderActor(val order: Order) extends Actor with FSM[OrderState, Trace] {

  import OrderState._

  log.debug("Create order = " + order)

  startWith(Active, Trace(order.amount))

  when(Active) {
    case Event(fill@FillOrder(_, fillAmount), Trace(restAmount, actions)) =>
      if (restAmount - fillAmount == 0) goto(Filled) using Trace(0, actions :+ fill)
      else stay() using Trace(restAmount - fillAmount, actions :+ fill)
  }

  when(Filled) {
    case Event(e@FillOrder(_, _), _) => throw new IllegalLifeCycleEvent("Order already Filled", e)
  }

  when(Cancelled) {
    case e => throw new IllegalLifeCycleEvent("Order already Cancelled", e)
  }

  whenUnhandled {
    case Event(cancel@CancelOrder(cancelAmount), Trace(restAmount, actions)) =>
      goto(Cancelled) using Trace(restAmount - cancelAmount, actions :+ cancel)
  }

  onTransition {
    case Active -> Filled => log.debug("Order filled")
    case Active -> Cancelled => log.debug("Order cancelled")
  }
}