package com.ergodicity.core.trade

import akka.testkit.{TestProbe, TestActorRef, ImplicitSender, TestKit}
import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, GivenWhenThen, WordSpec}
import akka.event.Logging
import com.ergodicity.core.IsinId
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.{OptTrade, FutTrade}
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.StreamEvent.StreamData

class TradesDispatcherSpec extends TestKit(ActorSystem("TradesDispatcherSpec")) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll with BeforeAndAfter {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val isinId = IsinId(100)

  val futTrade = {
    val buff = ByteBuffer.allocate(1000)
    val trade = new FutTrade.deal(buff)
    trade.set_id_deal(100)
    trade.set_amount(1)
    trade.set_isin_id(isinId.id)
    trade
  }

  val optTrade = {
    val buff = ByteBuffer.allocate(1000)
    val trade = new OptTrade.deal(buff)
    trade.set_id_deal(100)
    trade.set_amount(1)
    trade.set_isin_id(isinId.id)
    trade
  }

  "Fut Trades Dispatcher" must {
    "subscribe stream events" in {
      val stream = TestProbe()
      val dispatcher = TestActorRef(new FutTradesDispatcher(self, stream.ref), "TradesDispatcher")
      stream.expectMsg(SubscribeStreamEvents(dispatcher))

    }

    "forward data messages" in {
      val dispatcher = TestActorRef(new FutTradesDispatcher(self, system.deadLetters), "TradesDispatcher")
      dispatcher ! StreamData(FutTrade.deal.TABLE_INDEX, futTrade.getData)
      expectMsgType[Trade]
    }
  }

  "Opt Trades Dispatcher" must {
    "subscribe stream events" in {
      val stream = TestProbe()
      val dispatcher = TestActorRef(new OptTradesDispatcher(self, stream.ref), "TradesDispatcher")
      stream.expectMsg(SubscribeStreamEvents(dispatcher))

    }

    "forward data messages" in {
      val dispatcher = TestActorRef(new OptTradesDispatcher(self, system.deadLetters), "TradesDispatcher")
      dispatcher ! StreamData(OptTrade.deal.TABLE_INDEX, optTrade.getData)
      expectMsgType[Trade]
    }
  }


}
