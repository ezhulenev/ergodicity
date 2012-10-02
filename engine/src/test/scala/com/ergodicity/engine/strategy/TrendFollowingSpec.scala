package com.ergodicity.engine.strategy

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit._
import akka.util.duration._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import com.ergodicity.core.trade.Trade
import org.joda.time.{DurationFieldType, DateTime}
import com.ergodicity.engine.strategy.PriceRegression.{Slope, PriceTrend}
import com.ergodicity.core.IsinId
import org.apache.commons.math3.stat.StatUtils

class TrendFollowingSpec extends TestKit(ActorSystem("TrendFollowingSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val id = TrendFollowing.TrendFollowing

  val SystemTrade = false
  val start = new DateTime(2012, 1, 1, 12, 0)

  "PriceRegressionActor" must {
    "return empty regression if insufficiend data available" in {
      val regression = TestActorRef(new PriceRegressionActor(self)(1.minute, 1.minute), "PriceRegression")

      regression ! Trade(1, 1, IsinId(1), 100, 1, start, SystemTrade)
      expectMsg(PriceTrend(None, None))
    }

    "calculate regression matching in window" in {
      val regression = TestActorRef(new PriceRegressionActor(self)(1.minute, 1.minute), "PriceRegression")

      regression ! Trade(1, 1, IsinId(1), 100, 1, start, SystemTrade)
      regression ! Trade(1, 1, IsinId(1), 100, 1, start.withFieldAdded(DurationFieldType.seconds(), 10), SystemTrade)
      regression ! Trade(1, 1, IsinId(1), 500, 1, start.withFieldAdded(DurationFieldType.seconds(), 20), SystemTrade)
      regression ! Trade(1, 1, IsinId(1), 500, 1, start.withFieldAdded(DurationFieldType.seconds(), 30), SystemTrade)

      receiveWhile(1.second) {
        case r => log.info("regression = " + r)
      }
    }

    "normilize" in {
      log.info("100 = " + StatUtils.normalize(Array(100.0, 110.0, 100)).toSeq)
      log.info("100 = " + StatUtils.normalize(Array(100.0, 100.0, 100)).toSeq)
      log.info("100000 = " + StatUtils.normalize(Array(-100000.0, -100010.0, -100020)).toSeq)
    }
  }
}