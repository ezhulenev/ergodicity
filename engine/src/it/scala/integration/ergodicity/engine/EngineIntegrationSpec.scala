package integration.ergodicity.engine

import akka.event.Logging
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import com.ergodicity.engine.Components.Manager
import com.ergodicity.engine.Engine
import com.ergodicity.engine.Engine.StartEngine
import java.util.concurrent.TimeUnit

class EngineIntegrationSpec extends TestKit(ActorSystem("EngineIntegrationSpec", com.ergodicity.engine.EngineSystemConfig)) with WordSpec with BeforeAndAfterAll {

  val log = Logging(system, "EngineIntegrationSpec")

  override def afterAll() {
    system.shutdown()
  }

  "Engine" must {
    "start" in {

      val engine = TestActorRef(new Engine with Manager, "Engine")

      engine ! StartEngine

      Thread.sleep(TimeUnit.DAYS.toMillis(1))
    }
  }

}

