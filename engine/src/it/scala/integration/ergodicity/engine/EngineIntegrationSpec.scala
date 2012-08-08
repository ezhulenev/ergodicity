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
import com.ergodicity.engine.service.{ManagedSessions, ManagedConnection}
import ru.micexrts.cgate.{Connection => CGConnection, P2TypeParser, CGate}
import com.ergodicity.cgate.config.Replication
import java.io.File
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.config.CGateConfig
import com.ergodicity.cgate.Connection.StartMessageProcessing

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

  def factory = new IntegrationEngine

  "Engine" must {
    "start" in {
      val engine = TestActorRef(factory, "Engine")

      engine ! StartEngine

      Thread.sleep(5000)

      engine.underlyingActor.Connection ! StartMessageProcessing(100.millis)

      Thread.sleep(TimeUnit.DAYS.toMillis(1))
    }
  }

  class IntegrationEngine extends Engine with IntegrationComponents
  with ServiceManagerComponent with ManagedConnection
  with CreateListenerComponent with FutInfoReplication with OptInfoReplication with ManagedSessions

  trait IntegrationComponents {
    val underlyingConnection = new CGConnection(RouterConnection())

    val optInfoReplication = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/opt_info.ini"), "CustReplScheme")

    val futInfoReplication = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/fut_info.ini"), "CustReplScheme")
  }

}

