package integration.ergodicity.engine

import akka.event.Logging
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import com.ergodicity.engine.Components.ServiceManagerComponent
import com.ergodicity.engine.Engine
import com.ergodicity.engine.Engine.StartEngine
import java.util.concurrent.TimeUnit
import com.ergodicity.engine.service.ManagedConnection
import ru.micexrts.cgate.{Connection => CGConnection, P2TypeParser, CGate}
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.config.CGateConfig
import java.io.File

class EngineIntegrationSpec extends TestKit(ActorSystem("EngineIntegrationSpec", com.ergodicity.engine.EngineSystemConfig)) with WordSpec with BeforeAndAfterAll {

  val log = Logging(system, "EngineIntegrationSpec")

  val Host = "localhost"
  val Port = 4001

  val RouterConnection = Tcp(Host, Port, system.name)

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
    P2TypeParser.setCharset("windows-1251")
  }

  override def afterAll() {
    system.shutdown()
    CGate.close()
  }

  "Engine" must {
    "start" in {

      val engine = TestActorRef(new Engine with ServiceManagerComponent with ManagedConnection {
        def underlyingConnection = new CGConnection(RouterConnection())
      }, "Engine")

      engine ! StartEngine

      Thread.sleep(TimeUnit.DAYS.toMillis(1))
    }
  }

}

