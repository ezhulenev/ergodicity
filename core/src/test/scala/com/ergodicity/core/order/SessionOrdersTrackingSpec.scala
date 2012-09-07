package com.ergodicity.core.order

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.{IsinId, Isin, OrderDirection, AkkaConfigurations}
import AkkaConfigurations._
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.FutTrade
import java.math.BigDecimal
import com.ergodicity.core.OrderType.GoodTillCancelled

class SessionOrdersTrackingSpec extends TestKit(ActorSystem("SessionOrdersTrackingSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val orderId = 2876875842l

  "SessionOrdersTracking" must {
    "create new order" in {
      val orders = TestActorRef(new SessionOrdersTracking(4072), "SessionOrdersTracking")
      val underlying = orders.underlyingActor
      orders ! Create(Order(orderId, 100, IsinId(100), GoodTillCancelled, OrderDirection.Buy, 123, 1))
      assert(underlying.orders.size == 1)
    }

    "cancel order" in {
      val orders = TestActorRef(new SessionOrdersTracking(4072), "SessionOrdersTracking")
      val underlying = orders.underlyingActor

      orders ! Create(Order(orderId, 100, IsinId(100), GoodTillCancelled, OrderDirection.Buy, 123, 1))

      val order = underlying.orders(orderId)
      order ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(order, OrderState.Active))

      orders ! Delete(orderId, 1)

      expectMsg(Transition(order, OrderState.Active, OrderState.Cancelled))
    }

    "fill order" in {
      val orders = TestActorRef(new SessionOrdersTracking(4072), "SessionOrdersTracking")
      val underlying = orders.underlyingActor

      orders ! Create(Order(orderId, 100, IsinId(100), GoodTillCancelled, OrderDirection.Buy, 123, 3))

      val order = underlying.orders(orderId)
      order ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(order, OrderState.Active))

      orders ! Fill(orderId, 0l, 2, 100, 1)
      orders ! Fill(orderId, 0l, 1, 100, 0)

      expectMsg(Transition(order, OrderState.Active, OrderState.Filled))
    }
  }
}