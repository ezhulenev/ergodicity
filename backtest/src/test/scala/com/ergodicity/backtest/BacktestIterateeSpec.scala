package com.ergodicity.backtest

import com.ergodicity.marketdb.model._
import org.joda.time.DateTime
import org.scala_tools.time.Implicits._
import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.ActorSystem
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import scalaz.IterV
import scalaz.IterV.{El, Cont, Done}

class BacktestIterateeSpec extends TestKit(ActorSystem("BacktestEngineSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  val SystemTrade = false

  override def afterAll() {
    system.shutdown()
  }

  val NoSystem = true

  val market = Market("RTS")
  val security = Security("RTS 3.12")
  val time = new DateTime

  "Backtest Iteratee" must {
    "iterate over market payloads" in {
      val Count = 10

      val tradePayloads = for (i <- 1 to Count) yield TradePayload(market, security, i, BigDecimal("111"), 1, time + i.second, NoSystem)


      val orderPayloads = for (i <- 1 to Count * 2) yield OrderPayload(market, security, i, time + 500.millis + i.second, 0, 0, 0, BigDecimal("222"), 1, 1, None)

      val payloads = (tradePayloads ++ orderPayloads).toList

      val iterv = enumerate(payloads, BacktestIteratee.dispatcher[MarketPayload])
      val report = iterv.run

      log.info("Report = " + report)
      assert(report.trades == Count)
      assert(report.orders == 2 * Count)
    }
  }

  private def enumerate[E, A]: (List[E], IterV[E, A]) => IterV[E, A] = {
    case (Nil, i) => i
    case (_, i@Done(_, _)) => i
    case (x :: xs, Cont(k)) => enumerate(xs, k(El(x)))
  }
}