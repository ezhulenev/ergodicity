package com.ergodicity.engine.strategy

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit._
import akka.util.duration._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import com.ergodicity.core.trade.Trade
import org.joda.time.{DurationFieldType, DateTime}
import com.ergodicity.engine.strategy.PriceRegression.PriceSlope
import com.ergodicity.core.IsinId

class TrendFollowingSpec extends TestKit(ActorSystem("TrendFollowing", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val id = TrendFollowing.TrendFollowing

  val SystemTrade = false
  val start = new DateTime(2012, 1, 1, 12, 0)

  "PriceRegressionActor" must {
    "return empty regression if insufficiend data available" in {
      val regression = TestActorRef(new PriceRegression(self)(1.minute, 1.minute), "PriceRegression")

      regression ! Trade(1, 1, IsinId(1), 100, 1, start, SystemTrade)
      val slope = receiveOne(100.millis).asInstanceOf[PriceSlope]
      assert(slope.primary.isNaN)
      assert(slope.secondary.isNaN)
    }

    "calculate regression matching in window" in {
      val regression = TestActorRef(new PriceRegression(self)(1.minute, 1.minute), "PriceRegression")

      regression ! Trade(1, 1, IsinId(1), 100, 1, start, SystemTrade)
      regression ! Trade(1, 1, IsinId(1), 110, 1, start.withFieldAdded(DurationFieldType.seconds(), 10), SystemTrade)
      regression ! Trade(1, 1, IsinId(1), 500, 1, start.withFieldAdded(DurationFieldType.seconds(), 20), SystemTrade)
      regression ! Trade(1, 1, IsinId(1), 500, 1, start.withFieldAdded(DurationFieldType.seconds(), 30), SystemTrade)
      regression ! Trade(1, 1, IsinId(1), 50, 1, start.withFieldAdded(DurationFieldType.seconds(), 40), SystemTrade)

      receiveWhile(1.second) {
        case r => log.info("regression = " + r)
      }
    }

    "discard outdated trades" in {
      val regression = TestFSMRef(new PriceRegression(self)(1.minute, 1.minute), "PriceRegression")

      regression ! Trade(1, 1, IsinId(1), 100, 1, start, SystemTrade)
      regression ! Trade(1, 1, IsinId(1), 150, 1, start.withFieldAdded(DurationFieldType.seconds(), 50), SystemTrade)
      assert(regression.stateData.primary.size == 2)
      log.info("Primary data = " + regression.stateData.primary)
      regression ! Trade(1, 1, IsinId(1), 120, 1, start.withFieldAdded(DurationFieldType.seconds(), 70), SystemTrade)
      assert(regression.stateData.primary.size == 2)
      log.info("Primary data = " + regression.stateData.primary)

      receiveWhile(1.second) {
        case r => log.info("regression = " + r)
      }
    }
  }
}