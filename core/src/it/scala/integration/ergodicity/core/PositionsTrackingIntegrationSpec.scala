package integration.ergodicity.core

import java.io.File
import java.util.concurrent.TimeUnit
import akka.actor.{Actor, Props, ActorSystem}
import akka.util.duration._
import integration.ergodicity.core.AkkaIntegrationConfigurations._
import ru.micexrts.cgate.{CGate, Connection => CGConnection, Listener => CGListener}
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate._
import config.ConnectionConfig.Tcp
import config.Replication.{ReplicationParams, ReplicationMode}
import config.{Replication, CGateConfig}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import akka.event.Logging
import akka.actor.FSM.Transition
import scala.Some
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.core.PositionsTracking

class PositionsTrackingIntegrationSpec extends TestKit(ActorSystem("PositionsTrackingIntegrationSpec", ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  val Host = "localhost"
  val Port = 4001

  val RouterConnection = Tcp(Host, Port, system.name)

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
  }

  override def afterAll() {
    system.shutdown()
    CGate.close()
  }

  "Positions Tracking" must {
    "should work" in {
      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = TestFSMRef(new Connection(underlyingConnection, Some(500.millis)), "Connection")

      val dataStream = TestFSMRef(new DataStream, "PositionsDataStream")

      // Listeners
      val listenerConfig = Replication("FORTS_POS_REPL", new File("cgate/scheme/Pos.ini"), "CustReplScheme")
      val underlyingListener = new CGListener(underlyingConnection, listenerConfig(), new DataStreamSubscriber(dataStream))
      val listener = TestFSMRef(new Listener(underlyingListener), "PosListener")

      // Create Positions actor
      TestFSMRef(new PositionsTracking(dataStream), "Positions")

      Thread.sleep(1000)

      // On connection Activated open listeners etc
      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) =>
            // Open Listener in Combined mode
            listener ! Listener.Open(ReplicationParams(ReplicationMode.Combined))

            // Process messages
            connection ! StartMessageProcessing(500.millis)
        }
      })))

      // Open connections and track it's status
      connection ! Connection.Open

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}