package com.ergodicity.core.trade

import akka.event.Logging
import akka.util.duration._
import akka.actor.ActorSystem
import java.util.concurrent.TimeUnit
import akka.testkit._
import org.scalatest.{BeforeAndAfter, GivenWhenThen, BeforeAndAfterAll, WordSpec}
import com.ergodicity.core._
import session.SessionActor.AssignedContents
import com.ergodicity.core.FutureContract
import trade.TradesTracking.SubscribeTrades
import org.joda.time.DateTime
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents

class TradesTrackingSpec extends TestKit(ActorSystem("TradesTrackingSpec")) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll with BeforeAndAfter {
  val log = Logging(system, self)

  implicit val TimeOut = akka.util.Timeout(100, TimeUnit.MILLISECONDS)
  val SYSTEM_DEAL = false

  override def afterAll() {
    system.shutdown()
  }

  val futureContract1 = FutureContract(IsinId(101), Isin("RTS-9.12"), ShortIsin("RIU2"), "Future contract")
  val futureContract2 = FutureContract(IsinId(102), Isin("RTS-12.12"), ShortIsin("RIZ2"), "Future contract")

  val assignedContents = AssignedContents(Set(futureContract1, futureContract2))

  "Trades Tracking" must {

    "forward trades for subscribed securities" in {
      val futTrade = TestProbe()
      val optTrade = TestProbe()
      val tradesTracking = TestFSMRef(new TradesTracking(futTrade.ref, optTrade.ref), "TradesTracking")

      futTrade.expectMsgType[SubscribeStreamEvents]
      optTrade.expectMsgType[SubscribeStreamEvents]

      tradesTracking ! assignedContents

      val trade1 = Trade(100, 100, IsinId(102), 100, 1, new DateTime, SYSTEM_DEAL)
      val trade2 = Trade(100, 100, IsinId(101), 100, 1, new DateTime, SYSTEM_DEAL)

      tradesTracking ! SubscribeTrades(self, futureContract1)
      tradesTracking ! trade1
      tradesTracking ! trade2
      expectMsg(trade2)
      tradesTracking ! SubscribeTrades(self, futureContract2)
      expectNoMsg(300.millis)
    }
  }
}