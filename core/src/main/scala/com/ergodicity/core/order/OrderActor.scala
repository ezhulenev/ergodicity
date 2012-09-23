package com.ergodicity.core.order

import akka.actor.{ActorRef, FSM, Actor}
import com.ergodicity.core.order.OrderActor.{OrderEvent, SubscribeOrderEvents, IllegalOrderEvent}

sealed trait OrderState

object OrderState {

  case object Active extends OrderState

  case object Filled extends OrderState

  case object Cancelled extends OrderState

}

case class Trace(rest: Int, actions: Seq[Action] = Seq()) {
  if (rest < 0) throw new IllegalArgumentException("Rest amount should be greater then 0")
}

// Order Actions

object OrderActor {

  case class SubscribeOrderEvents(ref: ActorRef)

  case class OrderEvent(order: Order, action: Action)

  class IllegalOrderEvent(msg: String, event: Any) extends RuntimeException(msg)

}

class OrderActor(val order: Order) extends Actor with FSM[OrderState, Trace] {

  import OrderState._

  var subscribers = Set[ActorRef]()

  startWith(Active, Trace(order.amount, Create(order) :: Nil))

  when(Active) {
    case Event(fill@Fill(amount, rest, _), Trace(restAmount, actions)) =>
      dispatch(fill)
      if (restAmount - amount != rest)
        throw new IllegalOrderEvent("Rest amounts doesn't match; Rest = " + (restAmount - amount) + ", expected = " + rest, fill)

      if (restAmount - amount == 0)
        goto(Filled) using Trace(0, actions :+ fill)
      else stay() using Trace(restAmount - amount, actions :+ fill)
  }

  when(Filled) {
    case Event(e@Fill(_, _, _), _) => throw new IllegalOrderEvent("Order already Filled", e)
  }

  when(Cancelled) {
    case e => throw new IllegalOrderEvent("Order already Cancelled", e)
  }

  whenUnhandled {
    case Event(cancel@Cancel(cancelAmount), Trace(restAmount, actions)) =>
      dispatch(cancel)
      goto(Cancelled) using Trace(restAmount - cancelAmount, actions :+ cancel)

    case Event(SubscribeOrderEvents(ref), trace) =>
      trace.actions foreach (ref ! OrderEvent(order, _))
      subscribers = subscribers + ref
      stay()
  }

  onTransition {
    case Active -> Filled => log.debug("Order filled")
    case Active -> Cancelled => log.debug("Order cancelled")
  }

  private def dispatch(action: Action) {
    subscribers foreach (_ ! OrderEvent(order, action))
  }
}