package com.ergodicity.engine

import component.{OptInfoDataStream, FutInfoDataStream, Plaza2ConnectionComponent}
import org.scalatest.WordSpec
import akka.event.Logging
import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKit}
import java.util.concurrent.TimeUnit
import java.io.File

class TradingEngineIntegrationSpec extends TestKit(ActorSystem("TradingEngineIntegrationSpec", ConfigWithDetailedLogging)) with WordSpec {

  val log = Logging(system, "TradingEngineIntegrationSpec")

  val actorSystem = system
  val config = new TradingEngineConfig {
    system = specified(actorSystem)
    optInfo = specified(new File("engine/schema/OptInfo.ini"))
    futInfo = specified(new File("engine/schema/FutInfo.ini"))

    def apply() = new TradingEngine with Plaza2ConnectionComponent with FutInfoDataStream with OptInfoDataStream {
      def optInfoIni = optInfo

      def futInfoIni = futInfo
    }
  }

  "Trading engine" must {
    "be constructed from config" in {
      val engine = TestFSMRef(config(), "TradeEngine")

      engine ! StartTradingEngine(config.connectionProperties)

      Thread.sleep(TimeUnit.SECONDS.toMillis(1))

      assert(engine.stateName == TradingEngineState.Initializing)

      Thread.sleep(TimeUnit.SECONDS.toMillis(15))
    }
  }

}

