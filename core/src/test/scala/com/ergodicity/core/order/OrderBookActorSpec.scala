package com.ergodicity.core.order

import akka.actor.{Terminated, ActorSystem}
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.event.Logging
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.core._
import org.scalatest.{BeforeAndAfterAll, WordSpec}

class OrderBookActorSpec extends TestKit(ActorSystem("OrderBookActorSpec", com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val orderId = 100
  val security = FutureContract(IsinId(1), Isin("RTS-9.12"), ShortIsin(""), "Future contract")

  "OrderBookActor" must {
    "create new order" in {
      val orders = TestActorRef(new OrderBookActor(security), "OrderBookActor")
      val underlying = orders.underlyingActor
      orders ! OrderAction(orderId, Create(Order(orderId, 100, IsinId(100), OrderDirection.Buy, 123, 1, 1)))
      assert(underlying.orders.size == 1)
    }

    "cancel order" in {
      val orders = TestActorRef(new OrderBookActor(security), "OrderBookActor")
      val underlying = orders.underlyingActor

      orders ! OrderAction(orderId, Create(Order(orderId, 100, IsinId(100), OrderDirection.Buy, 123, 1, 1)))

      val order = underlying.orders(orderId)
      watch(order)
      order ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(order, OrderState.Active))

      orders ! OrderAction(orderId, Cancel(1))

      expectMsg(Transition(order, OrderState.Active, OrderState.Cancelled))
      expectMsg(Terminated(order))
    }

    "fill order" in {
      val orders = TestActorRef(new OrderBookActor(security), "OrderBookActor")
      val underlying = orders.underlyingActor

      orders ! OrderAction(orderId, Create(Order(orderId, 100, IsinId(100), OrderDirection.Buy, 123, 3, 1)))

      val order = underlying.orders(orderId)
      order ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(order, OrderState.Active))

      orders ! OrderAction(orderId, Fill(2, 1, None))
      orders ! OrderAction(orderId, Fill(1, 0, None))

      expectMsg(Transition(order, OrderState.Active, OrderState.Filled))
    }    
  }
}