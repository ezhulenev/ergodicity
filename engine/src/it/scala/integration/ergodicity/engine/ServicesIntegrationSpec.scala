package integration.ergodicity.engine

import akka.testkit.{TestActorRef, TestKit}
import akka.actor.ActorSystem
import akka.util.duration._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.config.{Replication, CGateConfig}
import java.io.File
import com.ergodicity.engine.{Engine, Services}
import com.ergodicity.engine.service.{InstrumentData, TradingConnections, Connection}
import com.ergodicity.engine.underlying.{UnderlyingListener, ListenerFactory, UnderlyingTradingConnections, UnderlyingConnection}
import java.util.concurrent.TimeUnit
import com.ergodicity.engine.Services.StartAllServices
import com.ergodicity.engine.ReplicationScheme.{OptInfoReplication, FutInfoReplication}
import ru.micexrts.cgate
import cgate.{Connection => CGConnection, ISubscriber, P2TypeParser, CGate, Listener => CGListener}
import com.ergodicity.cgate.Connection.StartMessageProcessing

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

  class IntegrationEngine extends Engine with UnderlyingListener with UnderlyingConnection with UnderlyingTradingConnections with FutInfoReplication with OptInfoReplication {
    val underlyingConnection = new CGConnection(ReplicationConnection())

    def underlyingPublisherConnection = new CGConnection(PublisherConnection())

    def underlyingRepliesConnection = new CGConnection(RepliesConnection())

    val optInfoReplication = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/opt_info.ini"), "CustReplScheme")

    val futInfoReplication = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/fut_info.ini"), "CustReplScheme")

    def listenerFactory = new ListenerFactory {
      def apply(connection: CGConnection, config: String, subscriber: ISubscriber) = new CGListener(connection, config, subscriber)
    }
  }

  class IntegrationServices(val engine: IntegrationEngine) extends Services with Connection with TradingConnections with InstrumentData

  "Services" must {
    "support Connection Service" in {

      val underlyingEngine = TestActorRef(new IntegrationEngine, "Engine").underlyingActor
      val services = TestActorRef(new IntegrationServices(underlyingEngine), "Services")

      services ! StartAllServices

       Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }

}
