package com.ergodicity.core.order

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import akka.actor.{ActorRef, ActorSystem}
import com.ergodicity.cgate.scheme.FutTrade
import java.nio.ByteBuffer
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.DataStream
import akka.testkit.{TestProbe, TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.cgate.DataStream.{BindingSucceed, BindTable}

class FutureOrdersSpec extends TestKit(ActorSystem("FutureOrdersSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }


  "Future Orders" must {

    "bind data stream" in {
      val ds = TestFSMRef(new DataStream)
      val futOrders = TestFSMRef(new FutureOrders(ds), "FutureOrders")
      assert(futOrders.stateName == FutureOrdersState.Binded)
    }

    "create new session orders on request" in {
      val ds = TestFSMRef(new DataStream)
      val futOrders = TestFSMRef(new FutureOrders(ds), "FutureOrders")
      futOrders.setState(FutureOrdersState.Binded)

      futOrders ! TrackSession(100)
      expectMsgType[ActorRef]

      assert(futOrders.stateData.size == 1)
    }

    "drop session orders" in {
      val ds = TestFSMRef(new DataStream)
      val futOrders = TestFSMRef(new FutureOrders(ds), "FutureOrders")
      futOrders.setState(FutureOrdersState.Binded)

      futOrders ! TrackSession(100)
      futOrders ! DropSession(100)

      assert(futOrders.stateData.size == 0)
    }

    "handle DataInserted event" in {

      val records = (1 to 10).toList.map {case i =>
        val buff = ByteBuffer.allocate(100)
        val record = new FutTrade.orders_log(buff)
        record.set_sess_id(100+i)
        record.getData
      }

      val ds = TestFSMRef(new DataStream)
      val futOrders = TestFSMRef(new FutureOrders(ds), "FutureOrders")
      futOrders.setState(FutureOrdersState.Binded)

      records.foreach(futOrders ! StreamData(FutTrade.orders_log.TABLE_INDEX, _))

      assert(futOrders.stateData.size == 10)
    }
  }
}