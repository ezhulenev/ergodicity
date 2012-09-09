package com.ergodicity.core

import akka.testkit.{TestProbe, TestActorRef, ImplicitSender, TestKit}
import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, GivenWhenThen, WordSpec}
import akka.event.Logging
import position.{Position, PositionDynamics}
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.Pos
import java.math.BigDecimal
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.core.PositionsTracking.{PositionUpdated, PositionDiscarded}

class PositionsDispatcherSpec extends TestKit(ActorSystem("PositionsDispatcherSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll with BeforeAndAfter {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val isinId = IsinId(100)

  val position = {
    val buff = ByteBuffer.allocate(1000)
    val pos = new Pos.position(buff)
    pos.set_isin_id(isinId.id)
    pos.set_open_qty(0)
    pos.set_buys_qty(1)
    pos.set_sells_qty(0)
    pos.set_pos(1)
    pos.set_net_volume_rur(new BigDecimal(100))
    pos.set_last_deal_id(111)
    pos
  }

  val removePosition = {
    val buff = ByteBuffer.allocate(1000)
    val pos = new Pos.position(buff)
    pos.set_replID(1000)
    pos.set_replAct(1000)
    pos.set_isin_id(isinId.id)
    pos.set_buys_qty(2)
    pos.set_pos(2)
    pos.set_net_volume_rur(new BigDecimal(200))
    pos
  }

  "Positions Dispatcher" must {
    "subscribe stream events" in {
      val stream = TestProbe()
      val dispatcher = TestActorRef(new PositionsDispatcher(self, stream.ref), "PositionsDispatcher")
      stream.expectMsg(SubscribeStreamEvents(dispatcher))

    }
    "discard position" in {
      val dispatcher = TestActorRef(new PositionsDispatcher(self, system.deadLetters), "PositionsDispatcher")
      dispatcher ! StreamData(Pos.position.TABLE_INDEX, removePosition.getData)
      expectMsg(PositionDiscarded(isinId))
    }

    "update position" in {
      val dispatcher = TestActorRef(new PositionsDispatcher(self, system.deadLetters), "PositionsDispatcher")
      dispatcher ! StreamData(Pos.position.TABLE_INDEX, position.getData)
      expectMsg(PositionUpdated(isinId, Position(1), PositionDynamics(buys = 1, volume = 100, lastDealId = Some(111))))
    }
  }

}
