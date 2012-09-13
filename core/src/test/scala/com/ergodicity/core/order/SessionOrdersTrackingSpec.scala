package com.ergodicity.core.order

import akka.actor.ActorSystem
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.event.Logging
import akka.util.duration._
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.core.OrderType.GoodTillCancelled
import com.ergodicity.core.{IsinId, OrderDirection}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import com.ergodicity.core.order.OrdersTracking.{OrderRef, GetOrder}

class SessionOrdersTrackingSpec extends TestKit(ActorSystem("SessionOrdersTrackingSpec", com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
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
      order._2 ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(order._2, OrderState.Active))

      orders ! Delete(orderId, 1)

      expectMsg(Transition(order._2, OrderState.Active, OrderState.Cancelled))
    }

    "fill order" in {
      val orders = TestActorRef(new SessionOrdersTracking(4072), "SessionOrdersTracking")
      val underlying = orders.underlyingActor

      orders ! Create(Order(orderId, 100, IsinId(100), GoodTillCancelled, OrderDirection.Buy, 123, 3))

      val order = underlying.orders(orderId)
      order._2 ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(order._2, OrderState.Active))

      orders ! Fill(orderId, 0l, 2, 100, 1)
      orders ! Fill(orderId, 0l, 1, 100, 0)

      expectMsg(Transition(order._2, OrderState.Active, OrderState.Filled))
    }

    "get order immediately" in {
      val orders = TestActorRef(new SessionOrdersTracking(4072), "SessionOrdersTracking")
      orders ! Create(Order(orderId, 100, IsinId(100), GoodTillCancelled, OrderDirection.Buy, 123, 1))
      orders ! GetOrder(orderId)
      val reply = receiveOne(100.millis)
      assert(reply match {
        case OrderRef(o, ref) => log.info("Got order = " + o +", ref = "+ref); true
        case _ => false
      })
    }

    "get order after it's created" in {
      val orders = TestActorRef(new SessionOrdersTracking(4072), "SessionOrdersTracking")
      orders ! GetOrder(orderId)
      orders ! Create(Order(orderId, 100, IsinId(100), GoodTillCancelled, OrderDirection.Buy, 123, 1))
      val reply = receiveOne(100.millis)
      assert(reply match {
        case OrderRef(o, ref) => log.info("Got order = " + o +", ref = "+ref); true
        case _ => false
      })
    }
  }
}