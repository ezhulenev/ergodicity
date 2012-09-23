package integration.ergodicity.core

import AkkaIntegrationConfigurations._
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import akka.actor.{Props, Actor, ActorSystem}
import akka.event.Logging
import akka.pattern.ask
import akka.pattern.pipe
import akka.testkit.{TestActorRef, ImplicitSender, TestFSMRef, TestKit}
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate._
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.config.Replication._
import com.ergodicity.core.order.{OrderBooksTracking, OrdersSnapshotActor}
import com.ergodicity.core.order.OrdersSnapshotActor.{OrdersSnapshot, GetOrdersSnapshot}
import config.{Replication, CGateConfig}
import java.io.File
import java.util.concurrent.TimeUnit
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{Connection => CGConnection, Listener => CGListener, P2TypeParser, CGate}
import com.ergodicity.core.order.OrderBooksTracking.Snapshots
import com.ergodicity.core.SessionsTracking
import com.ergodicity.core.SessionsTracking.{OngoingSessionTransition, OngoingSession, SubscribeOngoingSessions}
import com.ergodicity.core.session.SessionActor.GetAssignedContents

class OrderBooksTrackingIntegrationSpec extends TestKit(ActorSystem("OrderBooksTrackingIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
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

  implicit val timeout = Timeout(5.seconds)

  "OrdersSnapshotActor" must {
    "load snapshots from FORTS_FUT/OPTORDERBOOK_REPL" in {

      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = system.actorOf(Props(new Connection(underlyingConnection, Some(500.millis))), "Connection")

      val ordLog = system.actorOf(Props(new DataStream), "OrdLogStream")
      val futOrderBook = system.actorOf(Props(new DataStream), "FutOrderBookDataStream")
      val optOrderBook = system.actorOf(Props(new DataStream), "OptOrderBookDataStream")

      val FutInfoDataStream = system.actorOf(Props(new DataStream), "FutInfoDataStream")
      val OptInfoDataStream = system.actorOf(Props(new DataStream), "OptInfoDataStream")

      // Listeners
      val ordLogListenerConfig = Replication("FORTS_ORDLOG_REPL", new File("cgate/scheme/OrdLog.ini"), "CustReplScheme")
      val underlyingOrdLogListener = new CGListener(underlyingConnection, ordLogListenerConfig(), new DataStreamSubscriber(ordLog))
      val ordLogListener = system.actorOf(Props(new Listener(underlyingOrdLogListener)), "OrdLogListener")

      val futListenerConfig = Replication("FORTS_FUTORDERBOOK_REPL", new File("cgate/scheme/Orderbook.ini"), "CustReplScheme")
      val underlyingFutListener = new CGListener(underlyingConnection, futListenerConfig(), new DataStreamSubscriber(futOrderBook))
      val futListener = system.actorOf(Props(new Listener(underlyingFutListener)), "FutTradeListener")

      val optListenerConfig = Replication("FORTS_OPTORDERBOOK_REPL", new File("cgate/scheme/Orderbook.ini"), "CustReplScheme")
      val underlyingOptListener = new CGListener(underlyingConnection, optListenerConfig(), new DataStreamSubscriber(optOrderBook))
      val optListener = system.actorOf(Props(new Listener(underlyingOptListener)), "OptTradeListener")

      val futInfoListenerConfig = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/FutInfo.ini"), "CustReplScheme")
      val underlyingFutInfoListener = new CGListener(underlyingConnection, futInfoListenerConfig(), new DataStreamSubscriber(FutInfoDataStream))
      val futInfoListener = system.actorOf(Props(new Listener(underlyingFutInfoListener)), "FutInfoListener")

      val optInfoListenerConfig = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/OptInfo.ini"), "CustReplScheme")
      val underlyingOptInfoListener = new CGListener(underlyingConnection, optInfoListenerConfig(), new DataStreamSubscriber(OptInfoDataStream))
      val optInfoListener = system.actorOf(Props(new Listener(underlyingOptInfoListener)), "OptInfoListener")

      // Sessions
      val sessions = system.actorOf(Props(new SessionsTracking(FutInfoDataStream, OptInfoDataStream)), "SessionsTracking")

      // Snapshots
      val futuresSnapshot = system.actorOf(Props(new OrdersSnapshotActor(futOrderBook)), "FutturesSnapshot")
      val optionsSnapshot = system.actorOf(Props(new OrdersSnapshotActor(optOrderBook)), "OptionsSnapshot")

      // OrderBooks
      val orderBooks = system.actorOf(Props(new OrderBooksTracking(ordLog)), "OrderBooks")

      // Handle assigned contents
      sessions ! SubscribeOngoingSessions(system.actorOf(Props(new Actor {
        protected def receive = {
          case OngoingSession(id, ref) =>
            (ref ? GetAssignedContents) pipeTo orderBooks
            system.scheduler.scheduleOnce(5.seconds) {
              futListener ! Listener.Open(ReplicationParams(ReplicationMode.Snapshot))
              optListener ! Listener.Open(ReplicationParams(ReplicationMode.Snapshot))

              val fut = (futuresSnapshot ? GetOrdersSnapshot).mapTo[OrdersSnapshot]
              val opt = (optionsSnapshot ? GetOrdersSnapshot).mapTo[OrdersSnapshot]

              (fut zip opt).map(tuple => Snapshots(tuple._1, tuple._2)) onSuccess {case snapshots@Snapshots(f, o) =>
                log.info("Got snapshots; Futures = "+f.orders.size+"; Options = "+o.orders.size)

                /*(f.orders ++ o.orders).groupBy(_._1.id) foreach {
                  case (orderId, seq) =>
                  System.out.println("Order id = "+orderId+", seq  = "+seq)
                }*/

                orderBooks ! snapshots
                val params = ReplicationParams(ReplicationMode.Combined, Map("orders_log" -> scala.math.min(f.revision, o.revision)))
                log.info("Params = "+params())
                ordLogListener ! Listener.Open(params)
              }
            }

          case OngoingSessionTransition(_, OngoingSession(id, ref)) =>
            (ref ? GetAssignedContents) pipeTo orderBooks
        }
      })))


      Thread.sleep(1000)

      // On connection Activated open listeners etc
      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) =>
            // Open Listener in Combined mode
            futInfoListener ! Listener.Open(ReplicationParams(ReplicationMode.Snapshot))
            optInfoListener ! Listener.Open(ReplicationParams(ReplicationMode.Snapshot))

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