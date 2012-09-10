package com.ergodicity.capture

import akka.actor.ActorSystem
import akka.util.duration._
import akka.testkit.{TestProbe, TestActorRef, ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.StreamEvent.{StreamData, ClearDeleted, TnBegin, TnCommit}
import java.nio.ByteBuffer

class RouterSpec extends TestKit(ActorSystem("RouterSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Router" must {

    "subscribe stream events" in {
      val stream = TestProbe()
      val router = TestActorRef(new Router(stream.ref, Nil), "Router")
      stream.expectMsg(SubscribeStreamEvents(router))
    }

    "route only defined table" in {
      val stream = TestProbe()
      val router = TestActorRef(new Router(stream.ref, Route(self).table(0) :: Nil), "Router")

      router ! TnBegin
      expectMsg(TnBegin)

      router ! TnCommit
      expectMsg(TnCommit)

      router ! ClearDeleted(0, 100)
      expectMsg(ClearDeleted(0, 100))

      router ! ClearDeleted(1, 100)
      expectNoMsg(100.millis)

      router ! StreamData(0, ByteBuffer.allocate(100))
      expectMsgType[StreamData]

      router ! StreamData(1, ByteBuffer.allocate(100))
      expectNoMsg(100.millis)
    }
  }

}