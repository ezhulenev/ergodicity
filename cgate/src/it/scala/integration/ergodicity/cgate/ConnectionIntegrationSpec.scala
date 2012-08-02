package integration.ergodicity.cgate

import integration._
import akka.actor.ActorSystem
import akka.util.duration._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.cgate._
import com.ergodicity.cgate.Connection._
import config.CGateConfig
import ru.micexrts.cgate.{CGate, Connection => CGConnection}
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import java.io.File


class ConnectionIntegrationSpec extends TestKit(ActorSystem("ConnectionIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
  }

  override def afterAll() {
    system.shutdown()
    CGate.close()
  }

  val Host = "localhost"
  val Port = 4001

  val RouterConnection = Tcp(Host, Port, system.name)

  "Connection" must {
    "connect to CGate router in" in {
      val underlying = new CGConnection(RouterConnection())
      val connection = TestFSMRef(new Connection(underlying), "Connection")

      connection ! Open

      Thread.sleep(1000)

      log.info("Connection state = " + connection.stateName)
      assert(connection.stateName == Active)
    }
  }
}