package com.ergodicity.core.order

import akka.actor.ActorSystem
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import akka.event.Logging
import akka.pattern.ask
import akka.util.duration._
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.core.AkkaConfigurations._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.OrdBook
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.DataStreamState
import com.ergodicity.core.order.OrderBookSnapshot.{OrdersSnapshot, GetOrdersSnapshot}
import akka.util.Timeout
import akka.dispatch.Await

class OrderBookSnapshotSpec extends TestKit(ActorSystem("OrdersDispatchersSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val timeout = Timeout(1.second)

  val Order = {
    val buff = ByteBuffer.allocate(1000)
    val order = new OrdBook.orders(buff)
    order.set_action(1)
    order.set_sess_id(100)
    order.set_status(4157)
    order.set_dir(1)
    order.set_id_ord(1)
    order
  }

  val Info = {
    val buff = ByteBuffer.allocate(1000)
    val info = new OrdBook.info(buff)
    info.set_logRev(100)
    info
  }

  "Order Book Snapshot" must {

    "subscribe stream events and transitions" in {
      val snapshot = TestActorRef(new OrderBookSnapshot(self))
      expectMsg(SubscribeStreamEvents(snapshot))
      expectMsg(SubscribeTransitionCallBack(snapshot))
    }

    "return snapshot immediately" in {
      val snapshot = TestActorRef(new OrderBookSnapshot(self))
      expectMsg(SubscribeStreamEvents(snapshot))
      expectMsg(SubscribeTransitionCallBack(snapshot))

      snapshot ! StreamData(OrdBook.orders.TABLE_INDEX, Order.getData)
      snapshot ! StreamData(OrdBook.info.TABLE_INDEX, Info.getData)
      snapshot ! Transition(self, DataStreamState.Opened, DataStreamState.Closed)

      val ordersSnapshot = Await.result((snapshot ? GetOrdersSnapshot).mapTo[OrdersSnapshot], 1.second)
      assert(ordersSnapshot.revision == 100)
      assert(ordersSnapshot.actions.size == 1)
    }

    "return snapshot after loaded" in {
      val snapshot = TestActorRef(new OrderBookSnapshot(self))
      expectMsg(SubscribeStreamEvents(snapshot))
      expectMsg(SubscribeTransitionCallBack(snapshot))

      snapshot ! GetOrdersSnapshot
      snapshot ! StreamData(OrdBook.orders.TABLE_INDEX, Order.getData)
      snapshot ! StreamData(OrdBook.info.TABLE_INDEX, Info.getData)
      snapshot ! Transition(self, DataStreamState.Opened, DataStreamState.Closed)

      val ordersSnapshot = receiveOne(100.millis).asInstanceOf[OrdersSnapshot]
      assert(ordersSnapshot.revision == 100)
      assert(ordersSnapshot.actions.size == 1)
    }
  }
}