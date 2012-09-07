package com.ergodicity.core.order

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import akka.actor.{ActorRef, ActorSystem}
import com.ergodicity.cgate.scheme.FutTrade
import java.nio.ByteBuffer
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.{DataStreamState, DataStream}
import akka.testkit.{TestProbe, TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.cgate.DataStream.{BindingSucceed, BindTable}
import com.ergodicity.core.order.OrdersTracking.{DropSession, GetOrdersTracking}
import akka.actor.FSM.CurrentState

class OrdersTrackingSpec extends TestKit(ActorSystem("OrdersTrackingSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }


  "Future Orders" must {

    "bind data stream" in {
      val futDs = TestFSMRef(new DataStream)
      val optDs = TestFSMRef(new DataStream)
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs, optDs), "OrdersTracking")
      assert(ordersTracking.stateName == OrdersTrackingState.Binded)
    }

    "go to online state as streams goes online" in {
      val futDs = TestFSMRef(new DataStream)
      val optDs = TestFSMRef(new DataStream)
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs, optDs), "OrdersTracking")

      ordersTracking ! CurrentState(futDs, DataStreamState.Online)
      ordersTracking ! CurrentState(optDs, DataStreamState.Online)

      assert(ordersTracking.stateName == OrdersTrackingState.Online)
    }

    "create new session orders on request" in {
      val futDs = TestFSMRef(new DataStream)
      val optDs = TestFSMRef(new DataStream)
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs, optDs), "OrdersTracking")
      ordersTracking.setState(OrdersTrackingState.Binded)
      val underlying = ordersTracking.underlyingActor.asInstanceOf[OrdersTracking]

      ordersTracking ! GetOrdersTracking(100)
      expectMsgType[ActorRef]

      assert(underlying.sessions.size == 1)
    }

    "drop session orders" in {
      val futDs = TestFSMRef(new DataStream)
      val optDs = TestFSMRef(new DataStream)
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs, optDs), "OrdersTracking")
      ordersTracking.setState(OrdersTrackingState.Binded)
      val underlying = ordersTracking.underlyingActor.asInstanceOf[OrdersTracking]

      ordersTracking ! GetOrdersTracking(100)
      ordersTracking ! DropSession(100)

      assert(underlying.sessions.size == 0)
    }

    "handle DataInserted event" in {

      val records = (1 to 10).toList.map {case i =>
        val buff = ByteBuffer.allocate(100)
        val record = new FutTrade.orders_log(buff)
        record.set_sess_id(100+i)
        record.getData
      }

      val futDs = TestFSMRef(new DataStream)
      val optDs = TestFSMRef(new DataStream)
      val ordersTracking = TestFSMRef(new OrdersTracking(futDs, optDs), "OrdersTracking")
      ordersTracking.setState(OrdersTrackingState.Binded)
      val underlying = ordersTracking.underlyingActor.asInstanceOf[OrdersTracking]

      records.foreach(underlying.futuresDispatcher ! StreamData(FutTrade.orders_log.TABLE_INDEX, _))

      // Let dispatchers proceed messages
      Thread.sleep(100)

      assert(underlying.sessions.size == 10)
    }
  }
}