package com.ergodicity.core.order

import akka.testkit.{TestProbe, TestActorRef, ImplicitSender, TestKit}
import akka.actor.ActorSystem
import akka.util.duration._
import com.ergodicity.core.AkkaConfigurations._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.{OptTrade, FutTrade}
import com.ergodicity.core.IsinId
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.core.order.OrdersTracking.StickyAction

class OrdersDispatchersSpec extends TestKit(ActorSystem("OrdersDispatchersSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val isinId = IsinId(100)

  import com.ergodicity.cgate.Protocol._

  "Future Orders dispatcher" must {

    "subscribe stream events" in {
      val stream = TestProbe()
      val dispatcher = TestActorRef(new FutureOrdersDispatcher(self, stream.ref))
      stream.expectMsg(SubscribeStreamEvents(dispatcher))
    }

    "handle FutTrade stream events" in {
      val stream = TestProbe()
      val dispatcher = TestActorRef(new FutureOrdersDispatcher(self, stream.ref))

      val createOrder = {
        val buffer = ByteBuffer.allocate(1000)
        val record = new FutTrade.orders_log(buffer)
        record.set_action(1)
        record.set_sess_id(100)
        record.set_status(4157)
        record.set_dir(1)
        record
      }

      dispatcher ! StreamData(FutTrade.orders_log.TABLE_INDEX, createOrder.getData)
      val msg = receiveOne(100.millis)
      assert(msg match {
        case StickyAction(100, _: Create) =>
          log.debug("Action = " + msg)
          true
        case _ => false
      }, "Actual action = " + msg)
    }
  }

  "Option Orders dispatcher" must {
    "handle OptTrade stream events" in {
      val stream = TestProbe()
      val dispatcher = TestActorRef(new FutureOrdersDispatcher(self, stream.ref))

      val createOrder = {
        val buffer = ByteBuffer.allocate(1000)
        val record = new OptTrade.orders_log(buffer)
        record.set_action(1)
        record.set_sess_id(100)
        record.set_status(4157)
        record.set_dir(1)
        record
      }

      dispatcher ! StreamData(OptTrade.orders_log.TABLE_INDEX, createOrder.getData)
      val msg = receiveOne(100.millis)
      assert(msg match {
        case StickyAction(100, _: Create) =>
          log.debug("Action = " + msg)
          true
        case _ => false
      }, "Actual action = " + msg)
    }


  }
}
