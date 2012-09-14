package integration.ergodicity.engine

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{TestActorRef, TestKit}
import akka.util.duration._
import akka.pattern.ask
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.config.{FortsMessages, CGateConfig, Replication}
import com.ergodicity.core.{IsinId, Isin, ShortIsin, FutureContract}
import com.ergodicity.engine.ReplicationScheme.{PosReplication, OptInfoReplication, FutInfoReplication}
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.service.Trading.{ExecutionReport, Buy}
import com.ergodicity.engine.service._
import com.ergodicity.engine.underlying._
import com.ergodicity.engine.{ServicesActor, Engine}
import java.io.File
import java.util.concurrent.TimeUnit
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{Connection => CGConnection, ISubscriber, P2TypeParser, CGate, Listener => CGListener, Publisher => CGPublisher}
import akka.util.Timeout

class TradingIntegrationSpec extends TestKit(ActorSystem("ServicesIntegrationSpec", com.ergodicity.engine.EngineSystemConfig)) with WordSpec with BeforeAndAfterAll {

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

    val underlyingPublisherConnection = new CGConnection(PublisherConnection())

    val underlyingRepliesConnection = new CGConnection(RepliesConnection())
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

  class IntegrationServices(val engine: IntegrationEngine) extends ServicesActor with Connection with TradingConnections with InstrumentData with Portfolio with Trading

  "Trading Service" must {
    "fail buy bad contract" in {

      val underlyingEngine = TestActorRef(new IntegrationEngine, "Engine").underlyingActor
      val services = TestActorRef(new IntegrationServices(underlyingEngine), "Services")

      services ! StartServices

      Thread.sleep(TimeUnit.SECONDS.toMillis(10))

      val trading = services.underlyingActor.service(Trading.Trading)
      log.info("Trading service = " + trading)

      implicit val timeout = Timeout(10.second)
      val f = (trading ? Buy(FutureContract(IsinId(0), Isin("Badisin"), ShortIsin(""), ""), 1, 100)).mapTo[ExecutionReport]

      f onComplete {
        res => log.info("Result = " + res)
      }

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}
