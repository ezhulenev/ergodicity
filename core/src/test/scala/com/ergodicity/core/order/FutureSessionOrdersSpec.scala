package com.ergodicity.core.order

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import akka.actor.ActorSystem
import com.ergodicity.plaza2.scheme.common.OrderLogRecord
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}

class FutureSessionOrdersSpec extends TestKit(ActorSystem("FutureSessionOrdersSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val orderId = 2876875842l;
  val create = OrderLogRecord(1264067720l, 1264067720l, 0, orderId, 4072, "FZ00533", "2012/06/27 13:24:38.461", 1025, 1, 167566, 1, BigDecimal("130000.00000"), 3, 3, 0, "", "1900/01/01 00:00:00.000", 0, 0, BigDecimal("0.00000"))
  val fill1 = OrderLogRecord(1264067721l, 1264067721l, 0, orderId, 4072, "FZ00533", "2012/06/27 13:24:38.461", 4097, 2, 167566, 1, BigDecimal("130000.00000"), 1, 2, 0, "", "1900/01/01 00:00:00.000", 0, 28261086, BigDecimal("128695.00000"))
  val fill2 = OrderLogRecord(1264067721l, 1264067721l, 0, orderId, 4072, "FZ00533", "2012/06/27 13:24:38.461", 4097, 2, 167566, 1, BigDecimal("130000.00000"), 2, 0, 0, "", "1900/01/01 00:00:00.000", 0, 28261087, BigDecimal("128695.00000"))
  val cancel = OrderLogRecord(1264067721l, 1264067721l, 0, orderId, 4072, "FZ00533", "2012/06/27 13:24:38.461", 4097, 0, 167566, 1, BigDecimal("130000.00000"), 3, 0, 0, "", "1900/01/01 00:00:00.000", 0, 0, BigDecimal("0"))

  "FutureSessionOrders" must {
    "skip record with other session" in {
      val orders = TestActorRef(new FutureSessionOrders(1000), "FutureSessionOrders")
      val underlying = orders.underlyingActor
      orders ! create
      assert(underlying.orders.size == 0)
    }

    "create new order" in {
      val orders = TestActorRef(new FutureSessionOrders(4072), "FutureSessionOrders")
      val underlying = orders.underlyingActor
      orders ! create
      assert(underlying.orders.size == 1)
    }

    "cancel order" in {
      val orders = TestActorRef(new FutureSessionOrders(4072), "FutureSessionOrders")
      val underlying = orders.underlyingActor

      orders ! create

      val order = underlying.orders(orderId)
      order ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(order, OrderState.Active))

      orders ! cancel

      expectMsg(Transition(order, OrderState.Active, OrderState.Cancelled))
    }

    "fill order" in {
      val orders = TestActorRef(new FutureSessionOrders(4072), "FutureSessionOrders")
      val underlying = orders.underlyingActor

      orders ! create

      val order = underlying.orders(orderId)
      order ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(order, OrderState.Active))

      orders ! fill1
      orders ! fill2

      expectMsg(Transition(order, OrderState.Active, OrderState.Filled))
    }
  }
}