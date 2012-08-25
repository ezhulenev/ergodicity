package integration.ergodicity.engine

import akka.testkit.{TestActorRef, TestKit}
import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.config.CGateConfig
import java.io.File
import ru.micexrts.cgate.{P2TypeParser, CGate}
import com.ergodicity.engine.{Engine, Services}
import com.ergodicity.engine.service.{TradingConnections, Connection}
import com.ergodicity.engine.underlying.{UnderlyingTradingConnections, UnderlyingConnection}
import ru.micexrts.cgate.{Connection => CGConnection}
import java.util.concurrent.TimeUnit
import com.ergodicity.engine.Services.StartAllServices

class ServicesIntegrationSpec extends TestKit(ActorSystem("ServicesIntegrationSpec", com.ergodicity.engine.EngineSystemConfig)) with WordSpec with BeforeAndAfterAll {

  val log = Logging(system, "ServicesIntegrationSpec")

  val Host = "localhost"
  val Port = 4001

  val ReplicationConnection = Tcp(Host, Port, "Replication")
  val PublisherConnection = Tcp(Host, Port, "Publisher")
  val RepliesConnection = Tcp(Host, Port, "Repl")

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
    P2TypeParser.setCharset("windows-1251")
  }

  override def afterAll() {
    system.shutdown()
    CGate.close()
  }

  class IntegrationEngine extends Engine with UnderlyingConnection with UnderlyingTradingConnections {
    val underlyingConnection = new CGConnection(ReplicationConnection())

    def underlyingPublisherConnection = new CGConnection(PublisherConnection())

    def underlyingRepliesConnection = new CGConnection(RepliesConnection())
  }

  class IntegrationServices(val engine: IntegrationEngine) extends Services with Connection with TradingConnections

  "Services" must {
    "support Connection Service" in {

      val underlyingEngine = TestActorRef(new IntegrationEngine, "Engine").underlyingActor
      val services = TestActorRef(new IntegrationServices(underlyingEngine), "Services")

      services ! StartAllServices

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }

}
