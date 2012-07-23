package com.ergodicity.engine

import component._
import akka.event.Logging
import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKit}
import java.util.concurrent.TimeUnit
import java.io.File
import plaza2.{Connection => P2Connection}
import org.scalatest.{BeforeAndAfterAll, WordSpec}

class TradingEngineIntegrationSpec extends TestKit(ActorSystem("TradingEngineIntegrationSpec", ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll {

  val log = Logging(system, "TradingEngineIntegrationSpec")

  override def afterAll() {
    system.shutdown()
  }

  val actorSystem = system
  val config = new TradingEngineConfig {
    clientCode = "533"

    system = specified(actorSystem)
    optInfo = specified(new File("engine/schema/OptInfo.ini"))
    futInfo = specified(new File("engine/schema/FutInfo.ini"))
    pos = specified(new File("engine/schema/Pos.ini"))
    messages = specified(new File("engine/schema/Messages.ini"))

    def apply() = new TradingEngine(clientCode, processMessagesTimeout) with ConnectionComponent with FutInfoDataStream with OptInfoDataStream with PosDataStream with MessagesFromScheme {

      lazy val underlyingConnection = P2Connection()


      def messagesScheme = messages

      override def optInfoIni = Some(optInfo)

      override def futInfoIni = Some(futInfo)

      override def posIni = Some(pos)
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

