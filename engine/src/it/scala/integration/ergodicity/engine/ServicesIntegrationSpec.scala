package integration.ergodicity.engine

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{TestActorRef, TestKit}
import akka.util.duration._
import com.ergodicity.cgate.ListenerBinding
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.config.{Replication, CGateConfig, FortsMessages}
import com.ergodicity.engine.Listener._
import com.ergodicity.engine.ReplicationScheme._
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.service._
import com.ergodicity.engine.underlying._
import com.ergodicity.engine.{ServicesActor, Engine}
import java.io.File
import java.util.concurrent.TimeUnit
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{Connection => CGConnection, P2TypeParser, CGate, Publisher => CGPublisher}

class ServicesIntegrationSpec extends TestKit(ActorSystem("ServicesIntegrationSpec", com.ergodicity.engine.EngineSystemConfig)) with WordSpec with BeforeAndAfterAll {

  val log = Logging(system, "ServicesIntegrationSpec")

  val Host = "localhost"
  val Port = 4001

  val ReplicationConnection = Tcp(Host, Port, system.name + "Replication")
  val PublisherConnection = Tcp(Host, Port,  system.name + "Publisher")
  val RepliesConnection = Tcp(Host, Port,  system.name + "Repl")

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

  trait Replication extends FutInfoReplication with OptInfoReplication with PosReplication with FutOrdersReplication with OptOrdersReplication with OrdLogReplication with FutOrderBookReplication with OptOrderBookReplication with FutTradesReplication with OptTradesReplication {
    val optInfoReplication = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/OptInfo.ini"), "CustReplScheme")

    val futInfoReplication = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/FutInfo.ini"), "CustReplScheme")

    val posReplication = Replication("FORTS_POS_REPL", new File("cgate/scheme/Pos.ini"), "CustReplScheme")

    val futOrdersReplication = Replication("FORTS_FUTTRADE_REPL", new File("cgate/scheme/FutOrders.ini"), "CustReplScheme")

    val optOrdersReplication = Replication("FORTS_OPTTRADE_REPL", new File("cgate/scheme/OptOrders.ini"), "CustReplScheme")

    val futOrderbookReplication = Replication("FORTS_FUTORDERBOOK_REPL", new File("cgate/scheme/Orderbook.ini"), "CustReplScheme")

    val optOrderbookReplication = Replication("FORTS_OPTORDERBOOK_REPL", new File("cgate/scheme/Orderbook.ini"), "CustReplScheme")

    val ordLogReplication = Replication("FORTS_ORDLOG_REPL", new File("cgate/scheme/OrdLog.ini"), "CustReplScheme")

    val futTradesReplication = Replication("FORTS_FUTTRADE_REPL", new File("cgate/scheme/FutTrades.ini"), "CustReplScheme")

    val optTradesReplication = Replication("FORTS_OPTTRADE_REPL", new File("cgate/scheme/OptTrades.ini"), "CustReplScheme")
  }

  trait Listeners extends FutInfoListener with OptInfoListener with FutTradesListener with OptTradesListener {
    self: Connections with Replication =>

    val futInfoListener = ListenerBinding(underlyingConnection, futInfoReplication)

    val optInfoListener = ListenerBinding(underlyingConnection, optInfoReplication)

    val futTradesListener = ListenerBinding(underlyingConnection, futTradesReplication)

    val optTradesListener = ListenerBinding(underlyingConnection, optTradesReplication)
  }

  trait Publisher extends UnderlyingPublisher {
    self: Engine with UnderlyingTradingConnections =>
    val publisherName: String = "Engine"
    val brokerCode: String = "533"
    val messagesConfig = FortsMessages(publisherName, 5.seconds, new File("./cgate/scheme/FortsMessages.ini"))
    val underlyingPublisher = new CGPublisher(underlyingTradingConnection, messagesConfig())
  }

  class IntegrationEngine extends Engine with Connections with Replication with Listeners with Publisher

  class IntegrationServices(val engine: IntegrationEngine) extends ServicesActor with ReplicationConnection /*with TradingConnection*/ with InstrumentData /*with Portfolio with Trading */ /*with OrdersData*/ with TradesData

  "Services" must {
    "start all registered services" in {

      val underlyingEngine = TestActorRef(new IntegrationEngine, "Engine").underlyingActor
      val services = TestActorRef(new IntegrationServices(underlyingEngine), "Services")

      services ! StartServices

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}
