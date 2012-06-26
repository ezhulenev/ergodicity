package com.ergodicity.core.order

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import akka.actor.{ActorRef, ActorSystem}
import org.mockito.Mockito._
import com.ergodicity.plaza2.scheme.common.OrderLogRecord
import com.ergodicity.plaza2.DataStream.DataInserted

class FutureOrdersSpec extends TestKit(ActorSystem("FutureOrdersSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }


  "Future Orders" must {
    "create new session orders on request" in {
      val futOrders = TestActorRef(new FutureOrders, "FutureOrders")
      val underlying = futOrders.underlyingActor

      futOrders ! TrackSession(100)
      expectMsgType[ActorRef]

      assert(underlying.sessions.size == 1)
    }

    "drop session orders" in {
      val futOrders = TestActorRef(new FutureOrders, "FutureOrders")
      val underlying = futOrders.underlyingActor

      futOrders ! TrackSession(100)
      futOrders ! DropSession(100)

      assert(underlying.sessions.size == 0)
    }

    "handle DataInserted event" in {
      val records = (1 to 10).toList.map {case i =>
        val record = mock(classOf[OrderLogRecord])
        when(record.sess_id).thenReturn(100+i)
        record
      }

      val futOrders = TestActorRef(new FutureOrders, "FutureOrders")
      val underlying = futOrders.underlyingActor

      records.foreach(futOrders ! DataInserted("orders_log", _))

      assert(underlying.sessions.size == 10)
    }
  }
}