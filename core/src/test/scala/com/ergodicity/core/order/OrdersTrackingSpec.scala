package com.ergodicity.core.order

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActorRef, TestProbe, ImplicitSender, TestKit}
import com.ergodicity.core.order.OrdersTracking.{StickyAction, DropSession, GetSessionOrdersTracking}
import org.mockito.Mockito._

class OrdersTrackingSpec extends TestKit(ActorSystem("OrdersTrackingSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }


  "Orders tracking" must {

    "create new session orders on request" in {
      val futDs = TestProbe()
      val optDs = TestProbe()
      val ordersTracking = TestActorRef(new OrdersTracking(futDs.ref, optDs.ref), "OrdersTracking")
      val underlying = ordersTracking.underlyingActor

      ordersTracking ! GetSessionOrdersTracking(100)
      expectMsgType[ActorRef]

      assert(underlying.sessions.size == 1)
    }

    "drop session orders" in {
      val futDs = TestProbe()
      val optDs = TestProbe()
      val ordersTracking = TestActorRef(new OrdersTracking(futDs.ref, optDs.ref), "OrdersTracking")
      val underlying = ordersTracking.underlyingActor


      ordersTracking ! GetSessionOrdersTracking(100)
      ordersTracking ! DropSession(100)

      assert(underlying.sessions.size == 0)
    }

    "handle Sticky actions" in {
      val actions = (1 to 10).toList.map {case i =>
        StickyAction(i, Create(mock(classOf[Order])))
      }

      val futDs = TestProbe()
      val optDs = TestProbe()
      val ordersTracking = TestActorRef(new OrdersTracking(futDs.ref, optDs.ref), "OrdersTracking")
      val underlying = ordersTracking.underlyingActor

      actions.foreach(ordersTracking ! _)

      assert(underlying.sessions.size == 10)
    }
  }
}