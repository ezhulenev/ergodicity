package integration.ergodicity.cgate

import java.io.File
import integration._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.actor.{Props, Actor, ActorSystem}
import akka.pattern.ask
import akka.util.duration._
import com.ergodicity.cgate._
import config.ConnectionConfig.Tcp
import akka.actor.FSM.Transition
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.cgate.Connection.StartMessageProcessing
import config.{Replication, CGateConfig}
import repository.Repository
import repository.Repository.{Snapshot, SubscribeSnapshots}
import scheme.OrderBook
import com.ergodicity.cgate.config.Replication._
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import akka.event.Logging
import java.util.concurrent.TimeUnit
import com.ergodicity.cgate.Protocol._
import com.ergodicity.cgate.repository.ReplicaExtractor._
import com.ergodicity.cgate.DataStream.{BindingSucceed, BindingResult, BindTable}
import ru.micexrts.cgate.{P2TypeParser, CGate, Connection => CGConnection, Listener => CGListener}
import java.util
import akka.util.Timeout
import akka.dispatch.Await

class FutOrderBookIntegrationSpec extends TestKit(ActorSystem("FutOrderBookIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  val Host = "localhost"
  val Port = 4001

  val RouterConnection = Tcp(Host, Port, system.name)

  implicit val timeout = Timeout(1.second)

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
    P2TypeParser.setCharset("windows-1251")
  }

  override def afterAll() {
    system.shutdown()
    CGate.close()
  }

  "FutInfo DataStream" must {
    "load contents to Reportitory" in {
      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = system.actorOf(Props(new Connection(underlyingConnection)), "Connection")

      val FutOrderBookDataStream = system.actorOf(Props(new DataStream), "FutOrderBookDataStream")

      // Listener
      val listenerConfig = Replication("FORTS_FUTORDERBOOK_REPL", new File("cgate/scheme/orderbook.ini"), "CustReplScheme")
      val underlyingListener = new CGListener(underlyingConnection, listenerConfig(), new DataStreamSubscriber(FutOrderBookDataStream))
      val listener = system.actorOf(Props(new Listener(BindListener(underlyingListener) to connection)), "Listener")

      // Repository
      val ordersRepository = system.actorOf(Props(Repository[OrderBook.orders]), "OrdersRepository")
      val binding1 = (FutOrderBookDataStream ? BindTable(OrderBook.orders.TABLE_INDEX, ordersRepository)).mapTo[BindingResult]

      val infoRepository = system.actorOf(Props(Repository[OrderBook.info]), "InfoRepository")
      val binding2 = (FutOrderBookDataStream ? BindTable(OrderBook.info.TABLE_INDEX, infoRepository)).mapTo[BindingResult]

      val bindingResult = for {
        orders <- binding1
        info <- binding2
      } yield (orders, info)

      Await.result(bindingResult, 1.second) match {
        case b@(BindingSucceed(_, _), BindingSucceed(_, _)) => log.info("Binding succeed = " + b)
        case _ => throw new IllegalStateException("Failed Bind to data streams")
      }


      // Handle repository data
      ordersRepository ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot: Snapshot[OrderBook.orders] =>
            log.info("Got orders snapshot, size = " + snapshot.data.size)
            snapshot.data foreach {
              rec =>
                log.info("Order record; Order id = " + rec.get_id_ord() + ", revision = " + rec.get_replRev() + ", sess id = " + rec.get_sess_id())
            }
          case e => log.error("GOT = " + e)
        }
      }))

      infoRepository ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot: Snapshot[OrderBook.info] =>
            log.info("Got info snapshot, size = " + snapshot.data.size)
            snapshot.data foreach {
              rec =>
                log.info("Info record; Moment = " + new util.Date(rec.get_moment()) + ", revision = " + rec.get_logRev())
            }
          case e => log.error("GOT = " + e)
        }
      }))

      FutOrderBookDataStream ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case e => log.info("DATA STREAM STATE = " + e)
        }
      })))

      // On connection Activated open listeners etc
      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) =>
            // Open Listener in Combined mode
            listener ! Listener.Open(ReplicationParams(ReplicationMode.Snapshot))

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