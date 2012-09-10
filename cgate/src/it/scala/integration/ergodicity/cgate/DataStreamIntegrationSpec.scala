package integration.ergodicity.cgate

import java.io.File
import integration._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import akka.actor.{Cancellable, Actor, Props, ActorSystem}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import akka.util.duration._
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate._
import config.{Replication, CGateConfig}
import com.ergodicity.cgate.config.Replication._
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import akka.event.Logging
import java.util.concurrent.TimeUnit
import ru.micexrts.cgate.{P2TypeParser, CGate, Connection => CGConnection, Listener => CGListener}
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.StreamEvent.{TnBegin, StreamData}

class DataStreamIntegrationSpec extends TestKit(ActorSystem("DataStreamIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

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

  "DataStream" must {
    "go online" in {
      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = system.actorOf(Props(new Connection(underlyingConnection)), "Connection")

      val DataStream = system.actorOf(Props(new DataStream), "DataStream")

      // Listener
      val listenerConfig = Replication("FORTS_OPTTRADE_REPL", new File("cgate/scheme/OptTrade.ini"), "CustReplScheme")
      val underlyingListener = new CGListener(underlyingConnection, listenerConfig(), new DataStreamSubscriber(DataStream))
      val listener = system.actorOf(Props(new Listener(underlyingListener)), "Listener")


      DataStream ! SubscribeStreamEvents(TestActorRef(new StreamDataThrottler(10)))

      // On connection Activated open listeners etc
      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) =>
            // Open Listener in Combined mode
            listener ! Listener.Open(ReplicationParams(ReplicationMode.Combined, tables = Some(Set("orders_log"))))

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
