package com.ergodicity.core.order

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.core.IsinId
import com.ergodicity.core.OrderDirection._
import com.ergodicity.core.OrderType._
import com.ergodicity.core.order.OrderActor.{OrderEvent, SubscribeOrderEvents, IllegalLifeCycleEvent}
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}


class OrderActorSpec extends TestKit(ActorSystem("OrderActorSpec", com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val props = Order(100, 100, IsinId(111), GoodTillCancelled, Buy, BigDecimal(100), 1)

  "Order" must {
    "create new order in New state" in {
      val order = TestFSMRef(new OrderActor(props), "TestOrder")
      val underlying = order.underlyingActor.asInstanceOf[OrderActor]

      assert(underlying.order.amount == 1)
      assert(order.stateName == OrderState.Active)
      assert(order.stateData == Trace(1))
    }

    "move from Active to Filled" in {
      val order = TestFSMRef(new OrderActor(props), "TestOrder")

      when("subscribed for order events")
      order ! SubscribeOrderEvents(self)
      and("order filled")
      order ! FillOrder(1, None)

      then("order should be in Filled state")
      assert(order.stateName == OrderState.Filled)
      assert(order.stateData.rest == 0)
      assert(order.stateData.actions.size == 1)

      and("receive OrderEvent")
      expectMsg(OrderEvent(props, FillOrder(1, None)))
    }

    "stay in Active and move to Filled later" in {
      val order = props.copy(amount = 2)
      val orderActor = TestFSMRef(new OrderActor(order), "TestOrder")

      orderActor ! FillOrder(1, None)
      assert(orderActor.stateName == OrderState.Active)
      assert(orderActor.stateData.rest == 1)
      assert(orderActor.stateData.actions.size == 1)

      orderActor ! FillOrder(1, None)
      assert(orderActor.stateName == OrderState.Filled)
      assert(orderActor.stateData.rest == 0)
      assert(orderActor.stateData.actions.size == 2)

      when("subscribe to already filled order")
      orderActor ! SubscribeOrderEvents(self)
      then("should return all order events history")
      expectMsg(OrderEvent(order, FillOrder(1, None)))
      expectMsg(OrderEvent(order, FillOrder(1, None)))
    }

    "fail to fill more then rest amount" in {
      val order = TestFSMRef(new OrderActor(props), "TestOrder")

      intercept[IllegalArgumentException] {
        order.receive(FillOrder(100, None))
      }
    }

    "cancel order" in {
      val order = TestFSMRef(new OrderActor(props.copy(amount = 2)), "TestOrder")
      order ! CancelOrder(1)
      assert(order.stateName == OrderState.Cancelled)
    }

    "fail cancel order twice" in {
      val order = TestFSMRef(new OrderActor(props.copy(amount = 2)), "TestOrder")
      order ! CancelOrder(1)
      assert(order.stateName == OrderState.Cancelled)

      intercept[IllegalLifeCycleEvent] {
        order receive CancelOrder(1)
      }
    }
  }
}