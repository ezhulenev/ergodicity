package com.ergodicity.core.order

import akka.actor.{Terminated, PoisonPill, ActorSystem}
import akka.event.Logging
import akka.util.duration._
import akka.testkit._
import com.ergodicity.core.AkkaConfigurations._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import com.ergodicity.core.{SessionId, OrderDirection, IsinId}
import com.ergodicity.core.OrderType.GoodTillCancelled
import akka.actor.FSM.Transition
import com.ergodicity.core.order.OrdersTracking.OrderRef
import akka.actor.FSM.CurrentState
import com.ergodicity.core.order.OrdersTracking.OrderLog
import com.ergodicity.core.order.OrdersTracking.GetOrder
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.core.SessionsTracking.{OngoingSessionTransition, OngoingSession}
import org.mockito.Mockito._

class OrdersTrackingSpec extends TestKit(ActorSystem("OrdersTrackingSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val orderId = 12345l

  "Orders tracking" must {

    "initialize in CatchingOngoingSession state" in {
      val futDs = TestProbe()
      val optDs = TestProbe()
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs.ref, optDs.ref), "OrdersTracking")

      assert(ordersTracking.stateName == OrdersTrackingState.CatchingOngoingSession)
    }

    "move to Tracking state when receive ongoing session" in {
      val futDs = TestProbe()
      val optDs = TestProbe()
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs.ref, optDs.ref), "OrdersTracking")

      ordersTracking ! OngoingSession(SessionId(100, 100), self)

      assert(ordersTracking.stateName == OrdersTrackingState.Tracking)
    }


    "kill all orders when OngoingSession changes" in {
      val futDs = TestProbe()
      val optDs = TestProbe()
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs.ref, optDs.ref), "OrdersTracking")
      val underlying = ordersTracking.underlyingActor.asInstanceOf[OrdersTracking]

      ordersTracking.setState(OrdersTrackingState.Tracking, Some(OngoingSession(SessionId(100, 100), self)))

      val order = TestProbe()
      underlying.orders(111l) = (mock(classOf[Order]), order.ref)

      ordersTracking ! OngoingSessionTransition(OngoingSession(SessionId(100, 100), self), OngoingSession(SessionId(101, 101), self))

      watch(order.ref)
      expectMsg(Terminated(order.ref))

      assert(underlying.orders.size == 0)
    }

    "handle Order actions only for ongoing session" in {
      val futDs = TestProbe()
      val optDs = TestProbe()
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs.ref, optDs.ref), "OrdersTracking")
      val underlying = ordersTracking.underlyingActor.asInstanceOf[OrdersTracking]

      ordersTracking.setState(OrdersTrackingState.Tracking, Some(OngoingSession(SessionId(100, 100), self)))

      val actions = (0 to 10).toList.map {case i =>
        OrderLog(100 + i, OrderAction(i, Create(mock(classOf[Order]))))
      }

      actions.foreach(ordersTracking ! _)

      assert(underlying.orders.size == 1)
    }

    "create new order" in {
      val futDs = TestProbe()
      val optDs = TestProbe()
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs.ref, optDs.ref), "OrdersTracking")
      val underlying = ordersTracking.underlyingActor.asInstanceOf[OrdersTracking]

      ordersTracking.setState(OrdersTrackingState.Tracking, Some(OngoingSession(SessionId(100, 100), self)))
      ordersTracking ! OrderLog(100, OrderAction(orderId, Create(Order(orderId, 100, IsinId(100), GoodTillCancelled, OrderDirection.Buy, 123, 1))))
      assert(underlying.orders.size == 1)
    }

    "cancel order" in {
      val futDs = TestProbe()
      val optDs = TestProbe()
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs.ref, optDs.ref), "OrdersTracking")
      val underlying = ordersTracking.underlyingActor.asInstanceOf[OrdersTracking]

      ordersTracking.setState(OrdersTrackingState.Tracking, Some(OngoingSession(SessionId(100, 100), self)))

      ordersTracking ! OrderLog(100, OrderAction(orderId, Create(Order(orderId, 100, IsinId(100), GoodTillCancelled, OrderDirection.Buy, 123, 1))))

      val order = underlying.orders(orderId)
      order._2 ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(order._2, OrderState.Active))

      ordersTracking ! OrderLog(100, OrderAction(orderId, Cancel(1)))

      expectMsg(Transition(order._2, OrderState.Active, OrderState.Cancelled))
    }

    "fill order" in {
      val futDs = TestProbe()
      val optDs = TestProbe()
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs.ref, optDs.ref), "OrdersTracking")
      val underlying = ordersTracking.underlyingActor.asInstanceOf[OrdersTracking]

      ordersTracking.setState(OrdersTrackingState.Tracking, Some(OngoingSession(SessionId(100, 100), self)))

      ordersTracking ! OrderLog(100, OrderAction(orderId, Create(Order(orderId, 100, IsinId(100), GoodTillCancelled, OrderDirection.Buy, 123, 3))))

      val order = underlying.orders(orderId)
      order._2 ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(order._2, OrderState.Active))

      ordersTracking ! OrderLog(100, OrderAction(orderId, Fill(2, 1, None)))
      ordersTracking ! OrderLog(100, OrderAction(orderId, Fill(1, 0, None)))

      expectMsg(Transition(order._2, OrderState.Active, OrderState.Filled))
    }

    "get order immediately" in {
      val futDs = TestProbe()
      val optDs = TestProbe()
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs.ref, optDs.ref), "OrdersTracking")

      ordersTracking.setState(OrdersTrackingState.Tracking, Some(OngoingSession(SessionId(100, 100), self)))

      ordersTracking ! OrderLog(100, OrderAction(orderId, Create(Order(orderId, 100, IsinId(100), GoodTillCancelled, OrderDirection.Buy, 123, 1))))
      ordersTracking ! GetOrder(orderId)
      val reply = receiveOne(100.millis)
      assert(reply match {
        case OrderRef(o, ref) => log.info("Got order = " + o +", ref = "+ref); true
        case _ => false
      })
    }

    "get order after it's created" in {
      val futDs = TestProbe()
      val optDs = TestProbe()
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs.ref, optDs.ref), "OrdersTracking")

      ordersTracking.setState(OrdersTrackingState.Tracking, Some(OngoingSession(SessionId(100, 100), self)))

      ordersTracking ! GetOrder(orderId)
      ordersTracking ! OrderLog(100, OrderAction(orderId, Create(Order(orderId, 100, IsinId(100), GoodTillCancelled, OrderDirection.Buy, 123, 1))))
      val reply = receiveOne(100.millis)
      assert(reply match {
        case OrderRef(o, ref) => log.info("Got order = " + o +", ref = "+ref); true
        case _ => false
      })
    }
  }
}