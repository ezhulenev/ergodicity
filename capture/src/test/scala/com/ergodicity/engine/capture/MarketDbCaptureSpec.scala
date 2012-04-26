package com.ergodicity.engine.capture

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.slf4j.LoggerFactory
import akka.actor.ActorSystem
import org.mockito.Mockito._
import com.twitter.finagle.kestrel.Client
import com.ergodicity.engine.plaza2.scheme.{OrdLog, FutTrade}
import com.ergodicity.marketdb.model.{Market, Security, OrderPayload, TradePayload}
import org.joda.time.DateTime
import akka.testkit.{TestFSMRef, TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.engine.plaza2.DataStream.{DataEnd, DataInserted, DataBegin, DataDeleted}
import org.mockito.Matchers._
import com.twitter.concurrent.Offer
import org.jboss.netty.buffer.ChannelBuffer
import org.hamcrest.CoreMatchers._
import com.twitter.util.Promise

class MarketDbCaptureSpec extends TestKit(ActorSystem("MarketDbCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[MarketDbCaptureSpec])

  override def afterAll() {
    system.shutdown()
  }

  "MarketDbCapture" must {

    "fail on DataDeleted event" in {
      val revisionTracker = mock(classOf[StreamRevisionTracker])
      lazy val marketDbBuncher = new TradesBuncher(mock(classOf[Client]), "Trades")

      val capture = TestActorRef(new MarketDbCapture(revisionTracker, marketDbBuncher)(mock(classOf[(FutTrade.DealRecord) => TradePayload])))
      intercept[MarketCaptureException] {
        capture.receive(DataDeleted("table", 1))
      }
    }

    "flush market events and revisions on DataEnd" in {
      val revisionTracker = mock(classOf[StreamRevisionTracker])
      val client = mock(classOf[Client])
      when(client.write(argThat(is("Orders")), argThat(org.hamcrest.CoreMatchers.anything[Offer[ChannelBuffer]])))
        .thenReturn(new Promise[Throwable]())

      lazy val ordersBuncher = new OrdersBuncher(client, "Orders")
      val capture = TestFSMRef(new MarketDbCapture(revisionTracker, ordersBuncher)(orderConverter _), "MarketDbCapture")

      val record = mock(classOf[OrdLog.OrdersLogRecord])
      when(record.replRev).thenReturn(100)

      capture ! DataBegin
      assert(capture.stateName == MarketDbCaptureState.InTransaction)

      capture ! DataInserted("orders_log", record)
      capture ! DataEnd
      assert(capture.stateName == MarketDbCaptureState.Idle)
      
      Thread.sleep(100)

      verify(client).write(argThat(is("Orders")), argThat(org.hamcrest.CoreMatchers.anything[Offer[ChannelBuffer]]))
      verify(revisionTracker).setRevision("orders_log", 100)
    }

    "flush market revisions on market events flushed" in {
      val revisionTracker = mock(classOf[StreamRevisionTracker])
      val client = mock(classOf[Client])
      when(client.write(argThat(is("Orders")), argThat(org.hamcrest.CoreMatchers.anything[Offer[ChannelBuffer]])))
        .thenReturn(new Promise[Throwable]())

      lazy val ordersBuncher = new OrdersBuncher(client, "Orders")
      val capture = TestFSMRef(new MarketDbCapture(revisionTracker, ordersBuncher)(orderConverter _), "MarketDbCapture")

      val record = mock(classOf[OrdLog.OrdersLogRecord])
      when(record.replRev).thenReturn(100)

      capture ! DataBegin
      capture ! DataInserted("orders_log", record)

      val underlying = capture.underlyingActor.asInstanceOf[MarketDbCapture[OrdLog.OrdersLogRecord, OrderPayload]]
      underlying.marketBuncher ! FlushBunch

      capture ! DataEnd

      Thread.sleep(100)

      verify(client).write(argThat(is("Orders")), argThat(org.hamcrest.CoreMatchers.anything[Offer[ChannelBuffer]]))
      verify(revisionTracker).setRevision("orders_log", 100)
    }
  }

  def orderConverter(order: OrdLog.OrdersLogRecord) = OrderPayload(Market("RTS"), Security("RIH"), 1l, new DateTime, 100, 1, 1, BigDecimal("100"), 1, 1, None)
}