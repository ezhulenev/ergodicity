package integration.ergodicity.cgate

import integration._
import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.cgate._
import com.ergodicity.cgate.Connection._
import config.CGateConfig
import ru.micexrts.cgate.{CGate, Connection => CGConnection}
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.cgate.config.ConnectionType.Tcp
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
  val AppName = "ConnectionIntegrationSpec"

  val RouterConnection = Tcp(Host, Port, AppName)

  "Connection" must {
    "connect to CGate router in" in {
      val underlying = new CGConnection(RouterConnection())
      val connection = TestFSMRef(new Connection(underlying))

      connection ! Open

      Thread.sleep(1000)

      assert(connection.stateName == Active)
    }
  }
}