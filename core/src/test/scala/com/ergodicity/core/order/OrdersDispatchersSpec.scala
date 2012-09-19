package com.ergodicity.core.order

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{TestProbe, TestActorRef, ImplicitSender, TestKit}
import akka.util.duration._
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.Protocol
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.scheme.{OptOrder, FutOrder}
import com.ergodicity.core.AkkaConfigurations._
import com.ergodicity.core.IsinId
import com.ergodicity.core.order.OrdersTracking.OrderLog
import java.nio.ByteBuffer
import org.scalatest.{BeforeAndAfterAll, WordSpec}

class OrdersDispatchersSpec extends TestKit(ActorSystem("OrdersDispatchersSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val isinId = IsinId(100)


  "Future Orders dispatcher" must {

    "subscribe stream events" in {
      val stream = TestProbe()
      val dispatcher = TestActorRef(new FutureOrdersDispatcher(self, stream.ref)(Protocol.ReadsFutOrders))
      stream.expectMsg(SubscribeStreamEvents(dispatcher))
    }

    "handle FutTrade stream events" in {
      val stream = TestProbe()
      val dispatcher = TestActorRef(new FutureOrdersDispatcher(self, stream.ref)(Protocol.ReadsFutOrders))

      val createOrder = {
        val buffer = ByteBuffer.allocate(1000)
        val record = new FutOrder.orders_log(buffer)
        record.set_action(1)
        record.set_sess_id(100)
        record.set_status(4157)
        record.set_dir(1)
        record
      }

      dispatcher ! StreamData(FutOrder.orders_log.TABLE_INDEX, createOrder.getData)
      val msg = receiveOne(100.millis)
      assert(msg match {
        case OrderLog(100, OrderAction(0, _: Create)) =>
          log.debug("Action = " + msg)
          true
        case _ => false
      }, "Actual action = " + msg)
    }
  }

  "Option Orders dispatcher" must {
    "handle OptTrade stream events" in {
      val stream = TestProbe()
      val dispatcher = TestActorRef(new OptionOrdersDispatcher(self, stream.ref)(Protocol.ReadsOptOrders))

      val createOrder = {
        val buffer = ByteBuffer.allocate(1000)
        val record = new OptOrder.orders_log(buffer)
        record.set_action(1)
        record.set_sess_id(100)
        record.set_status(4157)
        record.set_dir(1)
        record
      }

      dispatcher ! StreamData(OptOrder.orders_log.TABLE_INDEX, createOrder.getData)
      val msg = receiveOne(100.millis)
      assert(msg match {
        case OrderLog(100, OrderAction(0, _: Create)) =>
          log.debug("Action = " + msg)
          true
        case _ => false
      }, "Actual action = " + msg)
    }


  }
}
