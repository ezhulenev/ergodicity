package integration.ergodicity.engine

import akka.event.Logging
import akka.util.duration._
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import com.ergodicity.engine.Components._
import com.ergodicity.engine.Engine
import com.ergodicity.engine.Engine.StartEngine
import java.util.concurrent.TimeUnit
import com.ergodicity.engine.service.{ManagedBrokerConnections, ManagedBroker, ManagedConnection}
import ru.micexrts.cgate.{Connection => CGConnection, P2TypeParser, CGate, Publisher => CGPublisher}
import com.ergodicity.cgate.config.{FortsMessages, Replication, CGateConfig}
import java.io.File
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.core.broker.Broker.Config

class EngineIntegrationSpec extends TestKit(ActorSystem("EngineIntegrationSpec", com.ergodicity.engine.EngineSystemConfig)) with WordSpec with BeforeAndAfterAll {

  val log = Logging(system, "EngineIntegrationSpec")

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

  def factory = new TestEngine

  "Engine" must {
    "start" in {
      val engine = TestActorRef(factory, "Engine")

      engine ! StartEngine

      Thread.sleep(5000)

      engine.underlyingActor.Connection ! StartMessageProcessing(100.millis)

      Thread.sleep(TimeUnit.DAYS.toMillis(1))
    }
  }

  class TestEngine extends Engine with Config with CreateListenerComponent
  with ManagedServices with ManagedConnection with ManagedBrokerConnections with ManagedBroker

  trait Config extends ConnectionConfig with SessionsConfig with BrokerConfig

  trait ConnectionConfig {
    val underlyingConnection = new CGConnection(ReplicationConnection())
  }

  trait BrokerConnectionsConfig {
    val underlyingPublisherConnection = new CGConnection(PublisherConnection())
    val underlyingRepliesConnection = new CGConnection(RepliesConnection())
  }

  trait BrokerConfig extends BrokerConnectionsConfig {
    implicit val BrokerConfig = Config("533")

    val BrokerName = "Ergodicity"

    val messagesConfig = FortsMessages(BrokerName, 5.seconds, new File("./cgate/scheme/forts_messages.ini"))
    val underlyingPublisher = new CGPublisher(underlyingPublisherConnection, messagesConfig())
  }

  trait SessionsConfig extends FutInfoReplication with OptInfoReplication {
    val optInfoReplication = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/opt_info.ini"), "CustReplScheme")

    val futInfoReplication = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/fut_info.ini"), "CustReplScheme")
  }

}

