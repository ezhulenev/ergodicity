package com.ergodicity.engine

import component.{PosDataStream, ConnectionComponent, OptInfoDataStream, FutInfoDataStream}
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
    pos = specified(new File("engine/schema/Pos.ini"))

    def apply() = new TradingEngine(processMessagesTimeout) with ConnectionComponent with FutInfoDataStream with OptInfoDataStream with PosDataStream {

      lazy val underlyingConnection = P2Connection()

      def optInfoIni = optInfo

      def futInfoIni = futInfo

      def posIni = pos
    }
  }

  "Trading engine" must {
    "be constructed from config" in {
      val engine = TestFSMRef(config(), "TradeEngine")

      engine ! StartTradingEngine(config.connectionProperties)

      Thread.sleep(TimeUnit.DAYS.toMillis(45))
    }
  }

}

