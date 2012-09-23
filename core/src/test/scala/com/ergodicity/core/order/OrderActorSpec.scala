package com.ergodicity.core.order

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.core.IsinId
import com.ergodicity.core.OrderDirection._
import com.ergodicity.core.OrderType._
import com.ergodicity.core.order.OrderActor.{OrderEvent, SubscribeOrderEvents, IllegalOrderEvent}
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}


class OrderActorSpec extends TestKit(ActorSystem("OrderActorSpec", com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val order = Order(100, 100, IsinId(111), GoodTillCancelled, Buy, BigDecimal(100), 1)

  "Order" must {
    "create new order in New state" in {
      val orderActor = TestFSMRef(new OrderActor(order), "TestOrder")
      val underlying = orderActor.underlyingActor.asInstanceOf[OrderActor]

      assert(underlying.order.amount == 1)
      assert(orderActor.stateName == OrderState.Active)
      assert(orderActor.stateData == Trace(1, Create(order) :: Nil))
    }

    "move from Active to Filled" in {
      val orderActor = TestFSMRef(new OrderActor(order), "TestOrder")

      when("subscribed for order events")
      orderActor ! SubscribeOrderEvents(self)
      and("order filled")
      orderActor ! Fill(1, 0, None)

      then("order should be in Filled state")
      assert(orderActor.stateName == OrderState.Filled)
      assert(orderActor.stateData.rest == 0)
      assert(orderActor.stateData.actions.size == 2)

      and("receive OrderEvent")
      expectMsg(OrderEvent(order, Create(order)))
      expectMsg(OrderEvent(order, Fill(1, 0, None)))
    }

    "stay in Active and move to Filled later" in {
      val orderCopy = order.copy(amount = 2)
      val orderActor = TestFSMRef(new OrderActor(orderCopy), "TestOrder")

      orderActor ! Fill(1, 1, None)
      assert(orderActor.stateName == OrderState.Active)
      assert(orderActor.stateData.rest == 1)
      assert(orderActor.stateData.actions.size == 2)

      orderActor ! Fill(1, 0, None)
      assert(orderActor.stateName == OrderState.Filled)
      assert(orderActor.stateData.rest == 0)
      assert(orderActor.stateData.actions.size == 3)

      when("subscribe to already filled order")
      orderActor ! SubscribeOrderEvents(self)
      then("should return all order events history")
      expectMsg(OrderEvent(orderCopy, Create(orderCopy)))
      expectMsg(OrderEvent(orderCopy, Fill(1, 1, None)))
      expectMsg(OrderEvent(orderCopy, Fill(1, 0, None)))
    }

    "fail to fill more then rest amount" in {
      val orderActor = TestFSMRef(new OrderActor(order), "TestOrder")

      intercept[IllegalArgumentException] {
        orderActor.receive(Fill(100, 1, None))
      }
    }

    "cancel order" in {
      val orderActor = TestFSMRef(new OrderActor(order.copy(amount = 2)), "TestOrder")
      orderActor ! Cancel(1)
      assert(orderActor.stateName == OrderState.Cancelled)
    }

    "fail cancel order twice" in {
      val orderActor = TestFSMRef(new OrderActor(order.copy(amount = 2)), "TestOrder")
      orderActor ! Cancel(1)
      assert(orderActor.stateName == OrderState.Cancelled)

      intercept[IllegalOrderEvent] {
        orderActor receive Cancel(1)
      }
    }
  }
}