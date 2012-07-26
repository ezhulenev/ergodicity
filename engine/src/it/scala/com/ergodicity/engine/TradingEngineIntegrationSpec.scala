package com.ergodicity.engine

import akka.event.Logging
import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKit}
import java.util.concurrent.TimeUnit
import java.io.File
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import com.ergodicity.cgate.config.Replication

class TradingEngineIntegrationSpec extends TestKit(ActorSystem("TradingEngineIntegrationSpec", ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll {

  val log = Logging(system, "TradingEngineIntegrationSpec")

  override def afterAll() {
    system.shutdown()
  }

  val actorSystem = system

  val config = new CGateTradingEngineConfig {
    system = actorSystem

    replicationScheme = ReplicationScheme(
      Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/fut_info.ini"), "CustReplScheme"),
      Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/opt_info.ini"), "CustReplScheme"),
      Replication("FORTS_POS_REPL", new File("cgate/scheme/pos.ini"), "CustReplScheme")
    )

  }

  "Trading engine" must {
    "be constructed from config" in {
      val engine = TestFSMRef(config(), "TradeEngine")

      engine ! StartTradingEngine

      Thread.sleep(TimeUnit.DAYS.toMillis(45))
    }
  }

}

