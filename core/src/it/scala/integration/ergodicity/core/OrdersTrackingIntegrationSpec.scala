package integration.ergodicity.core

import java.io.File
import akka.actor.{Actor, Props, ActorSystem}
import AkkaIntegrationConfigurations._
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import akka.testkit.{TestActorRef, ImplicitSender, TestFSMRef, TestKit}
import akka.util.duration._
import java.util.concurrent.TimeUnit
import com.ergodicity.core.order.OrdersTracking
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import ru.micexrts.cgate.{CGate, Connection => CGConnection, Listener => CGListener}
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate.config.Replication._
import com.ergodicity.cgate._
import config.{Replication, CGateConfig}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging

class OrdersTrackingIntegrationSpec extends TestKit(ActorSystem("OrdersTrackingIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
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

  "Future Orders" must {
    "handle orders from FORTS_FUTTRADE_REPL" in {

      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = TestFSMRef(new Connection(underlyingConnection, Some(500.millis)), "Connection")

      val futTrade = TestFSMRef(new DataStream, "FutTradeDataStream")
      val optTrade = TestFSMRef(new DataStream, "OptTradeDataStream")

      // Listeners
      val futListenerConfig = Replication("FORTS_FUTTRADE_REPL", new File("cgate/scheme/FutOrders.ini"), "CustReplScheme")
      val underlyingFutListener = new CGListener(underlyingConnection, futListenerConfig(), new DataStreamSubscriber(futTrade))
      val futListener = TestFSMRef(new Listener(underlyingFutListener), "FutTradeListener")

      val optListenerConfig = Replication("FORTS_OPTTRADE_REPL", new File("cgate/scheme/OptOrders.ini"), "CustReplScheme")
      val underlyingOptListener = new CGListener(underlyingConnection, optListenerConfig(), new DataStreamSubscriber(optTrade))
      val optListener = TestFSMRef(new Listener(underlyingOptListener), "OptTradeListener")

      // Construct OrdersTracking
      TestActorRef(new OrdersTracking(futTrade, optTrade), "OrdersTracking")

      Thread.sleep(1000)

      // On connection Activated open listeners etc
      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) =>
            // Open Listener in Combined mode
            futListener ! Listener.Open(ReplicationParams(ReplicationMode.Combined))
            optListener ! Listener.Open(ReplicationParams(ReplicationMode.Combined))


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