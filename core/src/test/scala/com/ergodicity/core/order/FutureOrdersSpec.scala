package com.ergodicity.core.order

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import akka.actor.{ActorRef, ActorSystem}
import org.mockito.Mockito._
import com.ergodicity.plaza2.scheme.common.OrderLogRecord
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.core.order.FutureOrders.BindFutTradeRepl
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.plaza2.DataStream.{BindTable, DataInserted}

class FutureOrdersSpec extends TestKit(ActorSystem("FutureOrdersSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }


  "Future Orders" must {

    "bind data stream" in {
      val futOrders = TestFSMRef(new FutureOrders, "FutureOrders")

      futOrders ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(futOrders, FutureOrdersState.Idle))

      futOrders ! BindFutTradeRepl(self)

      expectMsgType[BindTable[OrderLogRecord]]
      expectMsg(Transition(futOrders, FutureOrdersState.Idle, FutureOrdersState.Binded))
    }

    "create new session orders on request" in {
      val futOrders = TestFSMRef(new FutureOrders, "FutureOrders")
      futOrders.setState(FutureOrdersState.Binded)

      futOrders ! TrackSession(100)
      expectMsgType[ActorRef]

      assert(futOrders.stateData.size == 1)
    }

    "drop session orders" in {
      val futOrders = TestFSMRef(new FutureOrders, "FutureOrders")
      futOrders.setState(FutureOrdersState.Binded)

      futOrders ! TrackSession(100)
      futOrders ! DropSession(100)

      assert(futOrders.stateData.size == 0)
    }

    "handle DataInserted event" in {
      val records = (1 to 10).toList.map {case i =>
        val record = mock(classOf[OrderLogRecord])
        when(record.sess_id).thenReturn(100+i)
        record
      }

      val futOrders = TestFSMRef(new FutureOrders, "FutureOrders")
      futOrders.setState(FutureOrdersState.Binded)

      records.foreach(futOrders ! DataInserted("orders_log", _))

      assert(futOrders.stateData.size == 10)
    }
  }
}