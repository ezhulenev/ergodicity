package integration.ergodicity.engine

import akka.event.Logging
import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, WordSpec}

class TradingEngineIntegrationSpec extends TestKit(ActorSystem("TradingEngineIntegrationSpec", com.ergodicity.engine.ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll {

  val log = Logging(system, "TradingEngineIntegrationSpec")

  override def afterAll() {
    system.shutdown()
  }

  val actorSystem = system

}

