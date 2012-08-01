package integration.ergodicity.cgate

import integration._
import akka.actor.ActorSystem
import akka.util.duration._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.cgate._
import com.ergodicity.cgate.Connection._
import config.{Replication, CGateConfig}
import config.Replication.ReplicationMode.Combined
import config.Replication.ReplicationParams
import ru.micexrts.cgate.{Connection => CGConnection, Listener => CGListener, ErrorCode, CGate}
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import java.io.File
import ru.micexrts.cgate.messages.Message
import java.util.concurrent.TimeUnit

class ListenerIntegrationSpec extends TestKit(ActorSystem("ListenerIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
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
      val underlyingConnection = new CGConnection(RouterConnection())
      val connection = TestFSMRef(new Connection(underlyingConnection), "Connection")

      connection ! Open

      val listenerConfig = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/fut_info.ini"), "CustReplScheme")
      val underlyingListener = new CGListener(underlyingConnection, listenerConfig(), new Subscriber {
        def handleMessage(msg: Message) = ErrorCode.OK
      })
      val listener = TestFSMRef(new Listener(BindListener(underlyingListener) to connection), "Listener")
      listener ! Listener.Open(ReplicationParams(Combined))

      Thread.sleep(1000)

      log.info("Connection state = " + connection.stateName)
      log.info("Listener state = "+listener.stateName)

      assert(connection.stateName == Active)
      assert(listener.stateName == Active)

      listener ! Listener.Close

      Thread.sleep(1000)

      log.info("Connection state = " + connection.stateName)
      log.info("Listener state = "+listener.stateName)

      listener ! Listener.Open(ReplicationParams(Combined))

      Thread.sleep(1000)

      assert(listener.stateName == Active)
    }
  }
}