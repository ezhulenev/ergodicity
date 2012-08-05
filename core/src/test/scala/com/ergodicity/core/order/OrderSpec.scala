package com.ergodicity.core.order

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import com.ergodicity.core.common.{IsinId}
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import akka.actor.{Terminated, ActorSystem}
import com.ergodicity.core.common.OrderType._
import com.ergodicity.core.common.OrderDirection._


class OrderSpec extends TestKit(ActorSystem("OrderSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val props = OrderProps(100, 100, IsinId(111), GoodTillCancelled, Buy, BigDecimal(100), 1)

  "Order" must {
    "create new futOrder in New state" in {
      val order = TestFSMRef(new Order(props), "TestOrder")
      val underlying = order.underlyingActor.asInstanceOf[Order]
      
      assert(underlying.order.amount == 1)
      assert(order.stateName == OrderState.Active)
      assert(order.stateData == RestAmount(1))
    }

    "move from Active to Filled" in {
      val order = TestFSMRef(new Order(props), "TestOrder")

      order ! FillOrder(BigDecimal(100), 1)
      assert(order.stateName == OrderState.Filled)
      assert(order.stateData == RestAmount(0))
    }

    "stay in Active and move to Filled later" in {
      val order = TestFSMRef(new Order(props.copy(amount = 2)), "TestOrder")

      order ! FillOrder(BigDecimal(100), 1)
      assert(order.stateName == OrderState.Active)
      assert(order.stateData == RestAmount(1))

      order ! FillOrder(BigDecimal(100), 1)
      assert(order.stateName == OrderState.Filled)
      assert(order.stateData == RestAmount(0))
    }

    "fail to fill more then rest amount" in {
      val order = TestFSMRef(new Order(props), "TestOrder")

      intercept[IllegalArgumentException] {
        order.receive(FillOrder(BigDecimal(100), 100))
      }
    }

    "cancel futOrder" in {
      val order = TestFSMRef(new Order(props.copy(amount = 2)), "TestOrder")
      order ! CancelOrder(1)
      assert(order.stateName == OrderState.Cancelled)
    }

    "fail cancel futOrder twice" in {
      val order = TestFSMRef(new Order(props.copy(amount = 2)), "TestOrder")
      order ! CancelOrder(1)
      assert(order.stateName == OrderState.Cancelled)

      watch(order)
      order ! CancelOrder(1)
      expectMsg(Terminated(order))
    }
  }
}