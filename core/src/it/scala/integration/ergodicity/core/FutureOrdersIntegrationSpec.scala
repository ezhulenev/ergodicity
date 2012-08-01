package integration.ergodicity.core

import java.io.File
import akka.actor.{Actor, Props, ActorSystem}
import AkkaIntegrationConfigurations._
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import akka.util.duration._
import java.util.concurrent.TimeUnit
import com.ergodicity.core.order.FutureOrders
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import ru.micexrts.cgate.{CGate, Connection => CGConnection, Listener => CGListener}
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate.config.Replication._
import com.ergodicity.cgate._
import config.{Replication, CGateConfig}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging

class FutureOrdersIntegrationSpec extends TestKit(ActorSystem("FutureOrdersIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
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

      val connection = TestFSMRef(new Connection(underlyingConnection), "Connection")

      val dataStream = TestFSMRef(new DataStream, "FutTradeDataStream")

      // Listeners
      val listenerConfig = Replication("FORTS_FUTTRADE_REPL", new File("cgate/scheme/fut_trades.ini"), "CustReplScheme")
      val underlyingListener = new CGListener(underlyingConnection, listenerConfig(), new DataStreamSubscriber(dataStream))
      val listener = TestFSMRef(new Listener(BindListener(underlyingListener) to connection), "FutTradeListener")

      // Construct FutureOrders
      val futureOrders = TestFSMRef(new FutureOrders(dataStream), "FutureOrders")

      Thread.sleep(1000)

      // On connection Activated open listeners etc
      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) =>
            // Open Listener in Combined mode
            listener ! Listener.Open(ReplicationParams(ReplicationMode.Combined))

            // Process messages
            connection ! StartMessageProcessing(500.millis);
        }
      })))

      // Open connections and track it's status
      connection ! Connection.Open
      connection ! com.ergodicity.cgate.TrackUnderlyingStatus(500.millis)

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}