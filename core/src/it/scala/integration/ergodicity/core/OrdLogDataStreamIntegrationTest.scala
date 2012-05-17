package integration.ergodicity.core

import org.slf4j.LoggerFactory
import java.io.File
import org.scalatest.WordSpec
import plaza2.RequestType.CombinedDynamic
import plaza2.{TableSet, Connection => P2Connection, DataStream => P2DataStream}
import akka.actor.{Actor, Props, ActorSystem}
import com.ergodicity.plaza2.DataStream.{JoinTable, SetLifeNumToIni, Open}
import com.ergodicity.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import com.ergodicity.plaza2.Connection.{ProcessMessages, Connect}
import com.ergodicity.plaza2._
import AkkaIntegrationConfigurations._
import scheme.FutInfo.{Signs, SessContentsRecord}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import com.ergodicity.core.session.FutureContract
import scheme.{OrdLog, Deserializer}
import java.util.concurrent.{CountDownLatch, TimeUnit}

class OrdLogDataStreamIntegrationTest extends TestKit(ActorSystem("OrdLogDataStreamIntegrationTest", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec {
  val log = LoggerFactory.getLogger(classOf[OrdLogDataStreamIntegrationTest])


  val Host = "localhost"
  val Port = 4001
  val AppName = "OrdLogDataStreamIntegrationTest"

  val SessionContentToFuture = (record: SessContentsRecord) => new FutureContract(record.isin, record.shortIsin, record.isinId, record.name)

  def IsFuture(record: SessContentsRecord) = {
    val signs = Signs(record.signs)
    !signs.spot && !signs.moneyMarket && signs.anonymous
  }

  "DataStream" must {
    "do some stuff" in {
      val underlyingConnection = P2Connection()
      val connection = system.actorOf(Props(Connection(underlyingConnection)), "Connection")
      connection ! Connect(Host, Port, AppName)

      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, ConnectionState.Connected) => connection ! ProcessMessages(100);
        }
      })))

      val ini = new File("core/scheme/OrdLog.ini")
      val tableSet = TableSet(ini)
      val underlyingStream = P2DataStream("FORTS_ORDLOG_REPL", CombinedDynamic, tableSet)

      val dataStream = TestFSMRef(new DataStream(underlyingStream), "FORTS_ORDLOG_REPL")
      dataStream ! SetLifeNumToIni(ini)

      val ordersLogRepo = TestFSMRef(new Repository[OrdLog.OrdersLogRecord], "OrdersLogRepo")

      dataStream ! JoinTable("orders_log", ordersLogRepo, implicitly[Deserializer[OrdLog.OrdersLogRecord]])
      dataStream ! Open(underlyingConnection)

      val latch = new CountDownLatch(1)

      ordersLogRepo ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot: Snapshot[OrdLog.OrdersLogRecord] =>
            log.info("Got snapshot; size = " + snapshot.data.size)
            snapshot.data foreach {
              rec =>
                log.info("Record: " + rec)
            }
            latch.countDown()
        }
      }))

      latch.await(10, TimeUnit.SECONDS)
    }
  }
}