package com.ergodicity.engine

import component.Plaza2ConnectionComponent
import org.scalatest.WordSpec
import akka.event.Logging
import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKit}
import java.util.concurrent.TimeUnit

class TradingEngineIntegrationSpec extends TestKit(ActorSystem("TradingEngineIntegrationSpec", ConfigWithDetailedLogging)) with WordSpec {

  val log = Logging(system, "TradingEngineIntegrationSpec")

  val actorSystem = system
  val config = new TradingEngineConfig {
    system = specified(actorSystem)

    def apply() = new TradingEngine with Plaza2ConnectionComponent
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

