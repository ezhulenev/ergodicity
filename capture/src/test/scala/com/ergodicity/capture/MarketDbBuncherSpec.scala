package com.ergodicity.capture

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.slf4j.LoggerFactory
import akka.util.duration._
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.hamcrest.CoreMatchers._
import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.twitter.finagle.kestrel.Client
import org.joda.time.DateTime
import com.ergodicity.marketdb.model.{TradePayload, Market, Security}
import org.jboss.netty.buffer.ChannelBuffer
import com.twitter.concurrent.Offer
import com.twitter.util.Promise

class MarketDbBuncherSpec extends TestKit(ActorSystem("MarketDbBuncherSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[MarketDbBuncherSpec])

  val Queue = "trades"

  val market = Market("RTS")
  val security = Security("RTS 3.12")
  val time = new DateTime

  def payload(id: Long) = TradePayload(market, security, id, BigDecimal("111"), 1, time, true)

  override def afterAll() {
    system.shutdown()
  }

  "TradesBuncher" must {
    "be initialized in Idle state" in {
      val client = mock(classOf[Client])
      val buncher = TestFSMRef(new TradesBuncher(client, Queue))
      assert(buncher.stateName == BuncherState.Idle)
    }

    "accumulate trades revisions" in {
      val client = mock(classOf[Client])
      val buncher = TestFSMRef(new TradesBuncher(client, Queue))

      buncher ! BunchMarketEvent(payload(100))
      assert(buncher.stateName == BuncherState.Accumulating)

      buncher ! BunchMarketEvent(payload(101))
      buncher ! BunchMarketEvent(payload(102))

      assert(buncher.stateData.get.size == 3)
    }

    "flush table revisions on request" in {
      val client = mock(classOf[Client])
      when(client.write(argThat(is(Queue)), argThat(org.hamcrest.CoreMatchers.anything[Offer[ChannelBuffer]])))
        .thenReturn(new Promise[Throwable]())
      val buncher = TestFSMRef(new TradesBuncher(client, Queue))

      buncher ! BunchMarketEvent(payload(100))

      buncher ! FlushBunch

      Thread.sleep(1.second.toMillis)

      verify(client).write(argThat(is(Queue)), argThat(org.hamcrest.CoreMatchers.anything[Offer[ChannelBuffer]]))
      assert(buncher.stateName == BuncherState.Idle)
    }
  }
}
