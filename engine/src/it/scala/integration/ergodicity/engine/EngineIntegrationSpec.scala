package integration.ergodicity.engine

import akka.event.Logging
import akka.util.duration._
import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import com.ergodicity.engine.Components._
import com.ergodicity.engine.Engine
import com.ergodicity.engine.Engine.StartEngine
import java.util.concurrent.TimeUnit
import com.ergodicity.engine.service._
import ru.micexrts.cgate.{Connection => CGConnection, P2TypeParser, CGate, Publisher => CGPublisher}
import com.ergodicity.cgate.config.Replication
import java.io.File
import com.ergodicity.core.broker.Broker.Config
import com.ergodicity.cgate.config.CGateConfig
import com.ergodicity.cgate.config.FortsMessages
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.Connection.StartMessageProcessing

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
      val engine = TestFSMRef(factory, "Engine")

      engine ! StartEngine

      Thread.sleep(5000)

      engine.underlyingActor.asInstanceOf[Engine with Connection].Connection ! StartMessageProcessing(100.millis)

      Thread.sleep(10000)

      log.info("ENGINE STATE = " + engine.stateName)

      Thread.sleep(TimeUnit.DAYS.toMillis(1))
    }
  }

  class TestEngine extends Engine with Config with CreateListenerComponent
  with ManagedServices
  with ManagedStrategies
  with ManagedConnection
  //with ManagedInstrumentData
  with ManagedPortfolio
  with ManagedTradingConnections with ManagedTrading

  trait Config extends ConnectionConfig with SessionsConfig with PositionsConfig with TradingConfig

  trait ConnectionConfig {
    val underlyingConnection = new CGConnection(ReplicationConnection())
  }

  trait TradingConnectionsConfig {
    val underlyingPublisherConnection = new CGConnection(PublisherConnection())
    val underlyingRepliesConnection = new CGConnection(RepliesConnection())
  }

  trait TradingConfig extends TradingConnectionsConfig {
    implicit val BrokerConfig = Config("533")

    val BrokerName = "Ergodicity"

    val messagesConfig = FortsMessages(BrokerName, 5.seconds, new File("./cgate/scheme/forts_messages.ini"))
    val underlyingPublisher = new CGPublisher(underlyingPublisherConnection, messagesConfig())
  }

  trait SessionsConfig extends FutInfoReplication with OptInfoReplication {
    val optInfoReplication = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/opt_info.ini"), "CustReplScheme")

    val futInfoReplication = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/fut_info.ini"), "CustReplScheme")
  }

  trait PositionsConfig extends PosReplication {
    def posReplication = Replication("FORTS_POS_REPL", new File("cgate/scheme/pos.ini"), "CustReplScheme")
  }

}

