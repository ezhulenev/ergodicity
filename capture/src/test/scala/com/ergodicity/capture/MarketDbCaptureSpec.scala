package com.ergodicity.capture

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.slf4j.LoggerFactory
import akka.actor.ActorSystem
import org.mockito.Mockito._
import com.twitter.finagle.kestrel.Client
import com.ergodicity.marketdb.model.{Market, Security, OrderPayload}
import org.joda.time.DateTime
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import org.mockito.Matchers._
import com.twitter.concurrent.Offer
import org.jboss.netty.buffer.ChannelBuffer
import org.hamcrest.CoreMatchers._
import com.twitter.util.Promise
import com.ergodicity.cgate.scheme._
import com.ergodicity.cgate.StreamEvent.{TnCommit, StreamData, TnBegin}
import com.ergodicity.cgate.Reads
import java.nio.ByteBuffer
import com.ergodicity.capture.MarketDbCapture.ConvertToMarketDb
import com.ergodicity.cgate.scheme.OrdLog.orders_log

class MarketDbCaptureSpec extends TestKit(ActorSystem("MarketDbCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[MarketDbCaptureSpec])

  override def afterAll() {
    system.shutdown()
  }

  implicit val dummyOrderConverter = new ConvertToMarketDb[OrdLog.orders_log, OrderPayload] {
    def apply(in: orders_log) = OrderPayload(Market("RTS"), Security("RIH"), 1l, new DateTime, 100, 1, 1, BigDecimal("100"), 1, 1, None)
  }

  implicit val dummyReads = new Reads[OrdLog.orders_log] {
    def read(in: ByteBuffer) = new OrdLog.orders_log()
  }

  private def orderRecord(rev: Long) = {
    val buffer = ByteBuffer.allocate(1000)
    val rec = new OrdLog.orders_log(buffer)
    rec.set_replRev(rev)
    rec
  }

  "MarketDbCapture" must {
    "flush market events on TnCommit" in {
      val client = mock(classOf[Client])
      when(client.write(argThat(is("Orders")), argThat(org.hamcrest.CoreMatchers.anything[Offer[ChannelBuffer]])))
        .thenReturn(new Promise[Throwable]())

      lazy val ordersBuncher = new OrdersBuncher(client, "Orders")
      val capture = TestFSMRef(new MarketDbCapture[OrdLog.orders_log, OrderPayload](ordersBuncher), "MarketDbCapture")

      val record = orderRecord(100)

      capture ! TnBegin
      assert(capture.stateName == MarketDbCaptureState.InTransaction)

      capture ! StreamData(0, record.getData)
      capture ! TnCommit
      assert(capture.stateName == MarketDbCaptureState.Idle)

      Thread.sleep(100)

      verify(client).write(argThat(is("Orders")), argThat(org.hamcrest.CoreMatchers.anything[Offer[ChannelBuffer]]))
    }
  }
}