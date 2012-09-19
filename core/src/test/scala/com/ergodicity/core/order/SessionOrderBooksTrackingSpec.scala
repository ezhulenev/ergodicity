package com.ergodicity.core.order

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.core._
import com.ergodicity.core.order.OrderBooksTracking.StickyAction
import org.scalatest.{BeforeAndAfterAll, WordSpec}

class SessionOrderBooksTrackingSpec extends TestKit(ActorSystem("SessionOrderBooksTrackingSpec", com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val sessionId = 1
  val isinId = IsinId(1)
  val isin = Isin("RTS-9.12")
  val shortIsin = ShortIsin("")
  val security = FutureContract(isinId, isin, shortIsin, "Future contract")

  val orderId = 1000

  "SessionOrderBooksTracking" must {
    "create order book on request" in {
      val tracking = TestActorRef(new SessionOrderBooksTracking(sessionId), "SessionOrderBooksTracking")
      tracking ! StickyAction(security, OrderAction(orderId, Create(Order(orderId, sessionId, isinId, OrderType.GoodTillCancelled, OrderDirection.Buy, 100, 1))))
      assert(tracking.underlyingActor.orderBooks.size == 1, "Actual size = " + tracking.underlyingActor.orderBooks.size)
    }

    "pass through all acction to OrderBook actor" in {
      val tracking = TestActorRef(new SessionOrderBooksTracking(sessionId), "SessionOrderBooksTracking")
      tracking.underlyingActor.orderBooks(security) = self
      val action = OrderAction(orderId, Create(Order(orderId, sessionId, isinId, OrderType.GoodTillCancelled, OrderDirection.Buy, 100, 1)))
      tracking ! StickyAction(security, action)
      expectMsg(action)
    }
  }

}