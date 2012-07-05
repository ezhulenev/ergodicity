package com.ergodicity.engine

import component.{ConnectionComponent, OptInfoDataStream, FutInfoDataStream}
import org.scalatest.WordSpec
import akka.event.Logging
import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKit}
import java.util.concurrent.TimeUnit
import java.io.File
import plaza2.{Connection => P2Connection}

class TradingEngineIntegrationSpec extends TestKit(ActorSystem("TradingEngineIntegrationSpec", ConfigWithDetailedLogging)) with WordSpec {

  val log = Logging(system, "TradingEngineIntegrationSpec")

  val actorSystem = system
  val config = new TradingEngineConfig {
    system = specified(actorSystem)
    optInfo = specified(new File("engine/schema/OptInfo.ini"))
    futInfo = specified(new File("engine/schema/FutInfo.ini"))

    def apply() = new TradingEngine(processMessagesTimeout) with ConnectionComponent with FutInfoDataStream with OptInfoDataStream {

      lazy val underlyingConnection = P2Connection()

      def optInfoIni = optInfo

      def futInfoIni = futInfo
    }
  }

  "Trading engine" must {
    "be constructed from config" in {
      val engine = TestFSMRef(config(), "TradeEngine")

      engine ! StartTradingEngine(config.connectionProperties)

      Thread.sleep(TimeUnit.SECONDS.toMillis(1))

      //assert(engine.stateName == TradingEngineState.Initializing)

      Thread.sleep(TimeUnit.SECONDS.toMillis(45))
    }
  }

}

