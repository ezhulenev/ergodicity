package com.ergodicity.core.order

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.FutTrade
import java.math.BigDecimal

class SessionOrdersTrackingSpec extends TestKit(ActorSystem("SessionOrdersTrackingSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val orderId = 2876875842l
  val create = {
    val ord = baseOrder()
    ord.set_action(1)
    ord.set_status(1025)
    ord.set_amount(3)
    ord.set_amount_rest(3)
    ord
  }
  val fill1 = {
    val ord = baseOrder()
    ord.set_action(2)
    ord.set_status(4097)
    ord.set_amount(1)
    ord.set_amount_rest(2)
    ord.set_id_deal(28261086)
    ord.set_deal_price(new BigDecimal("128690.00000"))
    ord
  }
  val fill2 = {
    val ord = baseOrder()
    ord.set_action(2)
    ord.set_status(4097)
    ord.set_amount(2)
    ord.set_amount_rest(0)
    ord.set_id_deal(28261087)
    ord.set_deal_price(new BigDecimal("128695.00000"))
    ord
  }
  val cancel = {
    val ord = baseOrder()
    ord.set_action(0)
    ord.set_status(4097)
    ord.set_amount(3)
    ord.set_amount_rest(0)
    ord
  }

  "SessionOrdersTracking" must {
    "skip record with other session" in {
      val orders = TestActorRef(new SessionOrdersTracking(1000), "SessionOrdersTracking")
      val underlying = orders.underlyingActor
      orders ! create
      assert(underlying.orders.size == 0)
    }

    "create new futOrder" in {
      val orders = TestActorRef(new SessionOrdersTracking(4072), "SessionOrdersTracking")
      val underlying = orders.underlyingActor
      orders ! create
      assert(underlying.orders.size == 1)
    }

    "cancel futOrder" in {
      val orders = TestActorRef(new SessionOrdersTracking(4072), "SessionOrdersTracking")
      val underlying = orders.underlyingActor

      orders ! create

      val order = underlying.orders(orderId)
      order ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(order, OrderState.Active))

      orders ! cancel

      expectMsg(Transition(order, OrderState.Active, OrderState.Cancelled))
    }

    "fill futOrder" in {
      val orders = TestActorRef(new SessionOrdersTracking(4072), "SessionOrdersTracking")
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

  private def baseOrder() = {
    val buff = ByteBuffer.allocate(1000)
    val ord = new FutTrade.orders_log(buff)
    ord.set_id_ord(orderId)
    ord.set_sess_id(4072)
    ord.set_isin_id(167566)
    ord.set_dir(1)
    ord.set_price(new BigDecimal("130000.00000"))
    ord
  }
}