package integration.ergodicity.core

import java.io.File
import akka.actor.{Props, Actor, ActorSystem}
import AkkaIntegrationConfigurations._
import akka.testkit.{ImplicitSender, TestKit}
import java.util.concurrent.TimeUnit
import ru.micexrts.cgate.{Connection => CGConnection, Listener => CGListener, Publisher => CGPublisher, P2TypeParser, CGate}
import com.ergodicity.cgate.{Error => _, _}
import config.ConnectionConfig.Tcp
import config.Replies.RepliesParams
import config.{FortsMessages, Replies, CGateConfig}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.pattern.ask
import akka.util.duration._
import com.ergodicity.core.broker._
import com.ergodicity.core.Market.Futures
import com.ergodicity.core.Isin
import com.ergodicity.core.OrderType.GoodTillCancelled
import Broker._
import akka.actor.FSM.Transition
import scala.Some
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.cgate.Connection.StartMessageProcessing
import akka.util.Timeout
import akka.dispatch.Await

class BrokerIntegrationSpec extends TestKit(ActorSystem("BrokerIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  val Host = "localhost"
  val Port = 4001

  val RouterConnection = Tcp(Host, Port, system.name)

  implicit val timeout = Timeout(5.seconds)

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
    P2TypeParser.setCharset("windows-1251")
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
      val connection = system.actorOf(Props(new Connection(underlyingConnection, Some(500.millis))), "Connection")

      val messagesConfig = FortsMessages(BrokerName, 5.seconds, new File("./cgate/scheme/FortsMessages.ini"))
      val underlyingPublisher = new CGPublisher(underlyingConnection, messagesConfig())

      val broker = system.actorOf(Props(new Broker(underlyingPublisher)), "Broker")

      val underlyingListener = new CGListener(underlyingConnection, Replies(BrokerName)(), new ReplySubscriber(broker))
      val replyListener = system.actorOf(Props(new Listener(underlyingListener)), "ReplyListener")

      // On connection Activated open listeners etc
      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) =>
            // Open Listener &  Broker
            broker ! Broker.Open
            Thread.sleep(1000)
            replyListener ! Listener.Open(RepliesParams)

            // Process messages
            connection ! StartMessageProcessing(500.millis)
        }
      })))

      // Open connections and track it's status
      connection ! Connection.Open

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }

  "fail to buy bad contract" in {

    val underlyingConnection = new CGConnection(RouterConnection())
    val connection = system.actorOf(Props(new Connection(underlyingConnection, Some(500.millis))), "Connection")

    val messagesConfig = FortsMessages(BrokerName, 5.seconds, new File("./cgate/scheme/FortsMessages.ini"))
    val underlyingPublisher = new CGPublisher(underlyingConnection, messagesConfig())

    val broker = system.actorOf(Props(new Broker(underlyingPublisher)), "Broker")

    val underlyingListener = new CGListener(underlyingConnection, Replies(BrokerName)(), new ReplySubscriber(broker))
    val replyListener = system.actorOf(Props(new Listener(underlyingListener)), "ReplyListener")

    // On connection Activated open listeners etc
    connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
      protected def receive = {
        case Transition(_, _, Active) =>
          // Open Listener &  Broker
          broker ! Broker.Open
          Thread.sleep(2000)
          replyListener ! Listener.Open(RepliesParams)

          // Process messages
          connection ! StartMessageProcessing(100.millis)
      }
    })))

    // Open connections and track it's status
    connection ! Connection.Open

    Thread.sleep(3000)

    val f = (broker ? Buy[Futures](Isin("RTS-9.12"), 1, 100, GoodTillCancelled)).mapTo[OrderId]

    f onComplete {
      res => log.info("Result = " + res)
    }

    Thread.sleep(TimeUnit.DAYS.toMillis(10))
  }

  "order and cancel them later" in {

    val underlyingConnection = new CGConnection(RouterConnection())
    val connection = system.actorOf(Props(new Connection(underlyingConnection, Some(500.millis))), "Connection")

    val messagesConfig = FortsMessages(BrokerName, 5.seconds, new File("./cgate/scheme/FortsMessages.ini"))
    val underlyingPublisher = new CGPublisher(underlyingConnection, messagesConfig())

    val broker = system.actorOf(Props(new Broker(underlyingPublisher)), "Broker")

    val underlyingListener = new CGListener(underlyingConnection, Replies(BrokerName)(), new ReplySubscriber(broker))
    val replyListener = system.actorOf(Props(new Listener(underlyingListener)), "ReplyListener")

    // On connection Activated open listeners etc
    connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
      protected def receive = {
        case Transition(_, _, Active) =>
          // Open Listener &  Broker
          broker ! Broker.Open
          Thread.sleep(2000)
          replyListener ! Listener.Open(RepliesParams)

          // Process messages
          connection ! StartMessageProcessing(100.millis)
      }
    })))

    // Open connections and track it's status
    connection ! Connection.Open

    Thread.sleep(3000)

    val f1 = (broker ? Buy[Futures](Isin("RTS-9.12"), 2, 145000, GoodTillCancelled)).mapTo[OrderId]

    f1 onComplete (_ match {
      case Left(ActionFailedException(code, message)) =>
        log.error("Error placing order; Code = " + code + ", mess = " + message)

      case Right(order) =>
        log.info("Order Response = " + order)
        val f2 = (broker ? Cancel[Futures](order)).mapTo[Cancelled]
        val cancelled = Await.result(f2, 5.seconds)

        log.info("Cancel Response = " + cancelled)
    })

    Thread.sleep(TimeUnit.DAYS.toMillis(10))
  }
}
