package com.ergodicity.core.order

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import com.ergodicity.core.{IsinId}
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import akka.actor.{Terminated, ActorSystem}
import com.ergodicity.core.OrderType._
import com.ergodicity.core.OrderDirection._
import com.ergodicity.core.order.OrderActor.IllegalLifeCycleEvent


class OrderActorSpec extends TestKit(ActorSystem("OrderActorSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val props = Order(100, 100, IsinId(111), GoodTillCancelled, Buy, BigDecimal(100), 1)

  "Order" must {
    "create new futOrder in New state" in {
      val order = TestFSMRef(new OrderActor(props), "TestOrder")
      val underlying = order.underlyingActor.asInstanceOf[OrderActor]

      assert(underlying.order.amount == 1)
      assert(order.stateName == OrderState.Active)
      assert(order.stateData == RestAmount(1))
    }

    "move from Active to Filled" in {
      val order = TestFSMRef(new OrderActor(props), "TestOrder")

      order ! FillOrder(BigDecimal(100), 1)
      assert(order.stateName == OrderState.Filled)
      assert(order.stateData == RestAmount(0))
    }

    "stay in Active and move to Filled later" in {
      val order = TestFSMRef(new OrderActor(props.copy(amount = 2)), "TestOrder")

      order ! FillOrder(BigDecimal(100), 1)
      assert(order.stateName == OrderState.Active)
      assert(order.stateData == RestAmount(1))

      order ! FillOrder(BigDecimal(100), 1)
      assert(order.stateName == OrderState.Filled)
      assert(order.stateData == RestAmount(0))
    }

    "fail to fill more then rest amount" in {
      val order = TestFSMRef(new OrderActor(props), "TestOrder")

      intercept[IllegalArgumentException] {
        order.receive(FillOrder(BigDecimal(100), 100))
      }
    }

    "cancel futOrder" in {
      val order = TestFSMRef(new OrderActor(props.copy(amount = 2)), "TestOrder")
      order ! CancelOrder(1)
      assert(order.stateName == OrderState.Cancelled)
    }

    "fail cancel futOrder twice" in {
      val order = TestFSMRef(new OrderActor(props.copy(amount = 2)), "TestOrder")
      order ! CancelOrder(1)
      assert(order.stateName == OrderState.Cancelled)

      intercept[IllegalLifeCycleEvent] {
        order receive CancelOrder(1)
      }
    }
  }
}