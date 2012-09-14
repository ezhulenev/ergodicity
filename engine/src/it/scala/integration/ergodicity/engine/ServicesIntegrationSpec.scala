package integration.ergodicity.engine

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{TestActorRef, TestKit}
import akka.util.duration._
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.config.{FortsMessages, CGateConfig, Replication}
import com.ergodicity.engine.ReplicationScheme.{PosReplication, OptInfoReplication, FutInfoReplication}
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.service._
import com.ergodicity.engine.underlying._
import com.ergodicity.engine.{ServicesActor, Engine}
import java.io.File
import java.util.concurrent.TimeUnit
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{Connection => CGConnection, ISubscriber, P2TypeParser, CGate, Listener => CGListener, Publisher => CGPublisher}

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

  trait Connections extends UnderlyingConnection with UnderlyingTradingConnections {
    val underlyingConnection = new CGConnection(ReplicationConnection())

    val underlyingTradingConnection = new CGConnection(PublisherConnection())
  }

  trait Replication extends FutInfoReplication with OptInfoReplication with PosReplication {
    val optInfoReplication = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/OptInfo.ini"), "CustReplScheme")

    val futInfoReplication = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/FutInfo.ini"), "CustReplScheme")

    val posReplication = Replication("FORTS_POS_REPL", new File("cgate/scheme/Pos.ini"), "CustReplScheme")
  }

  trait Listener extends UnderlyingListener {
    val listenerFactory = new ListenerFactory {
      def apply(connection: CGConnection, config: String, subscriber: ISubscriber) = new CGListener(connection, config, subscriber)
    }
  }

  trait Publisher extends UnderlyingPublisher {
    self: Engine with UnderlyingConnection =>
    val publisherName: String = "Engine"
    val brokerCode: String = "533"
    val messagesConfig = FortsMessages(publisherName, 5.seconds, new File("./cgate/scheme/FortsMessages.ini"))
    val underlyingPublisher = new CGPublisher(underlyingConnection, messagesConfig())
  }

  class IntegrationEngine extends Engine with Connections with Replication with Listener with Publisher

  class IntegrationServices(val engine: IntegrationEngine) extends ServicesActor with ReplicationConnection with TradingConnection with InstrumentData with Portfolio with Trading

  "Services" must {
    "start all registered services" in {

      val underlyingEngine = TestActorRef(new IntegrationEngine, "Engine").underlyingActor
      val services = TestActorRef(new IntegrationServices(underlyingEngine), "Services")

      services ! StartServices

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}
