package integration.ergodicity.core

import java.io.File
import akka.actor.{Actor, Props, ActorSystem}
import AkkaIntegrationConfigurations._
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import akka.util.duration._
import java.util.concurrent.TimeUnit
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import ru.micexrts.cgate.{CGate, Connection => CGConnection, Listener => CGListener, Publisher => CGPublisher}
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate._
import config.Replies.RepliesParams
import config.{FortsMessages, Replies, CGateConfig}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.broker.{BindPublisher, ReplySubscriber, Broker}

class BrokerIntegrationSpec extends TestKit(ActorSystem("BrokerIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
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

  implicit val config = Broker.Config("533")
  val BrokerName = "TestBroker"

  "Broker" must {
    "go to Active state" in {

      val underlyingConnection = new CGConnection(RouterConnection())
      val connection = TestFSMRef(new Connection(underlyingConnection, Some(500.millis)), "Connection")

      val messagesConfig = FortsMessages(BrokerName, 5.seconds, new File("./cgate/scheme/forts_messages.ini"))
      val underlyingPublisher = new CGPublisher(underlyingConnection, messagesConfig())

      val broker = TestFSMRef(new Broker(BindPublisher(underlyingPublisher) to connection), "Broker")

      val underlyingListener = new CGListener(underlyingConnection, Replies(BrokerName)(), new ReplySubscriber(broker))
      val replyListener = TestFSMRef(new Listener(BindListener(underlyingListener) to connection), "ReplyListener")

      // On connection Activated open listeners etc
      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) =>
            // Open Listener &  Broker
            broker ! Broker.Open
            Thread.sleep(2000)
            replyListener ! Listener.Open(RepliesParams)

            // Process messages
            connection ! StartMessageProcessing(500.millis)
        }
      })))

      // Open connections and track it's status
      connection ! Connection.Open

      log.info("Broker state = " + broker.stateName)
      log.info("Listener state = " + replyListener.stateName)

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}
