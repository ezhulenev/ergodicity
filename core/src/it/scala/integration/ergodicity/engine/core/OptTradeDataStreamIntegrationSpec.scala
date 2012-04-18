package integration.ergodicity.engine.core

import org.slf4j.LoggerFactory
import java.io.File
import org.scalatest.WordSpec
import plaza2.RequestType.CombinedDynamic
import plaza2.{TableSet, Connection => P2Connection, DataStream => P2DataStream}
import akka.actor.{Actor, Props, ActorSystem}
import com.ergodicity.engine.plaza2.DataStream.{JoinTable, SetLifeNumToIni, Open}
import com.ergodicity.engine.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import com.ergodicity.engine.plaza2.Connection.{ProcessMessages, Connect}
import com.ergodicity.engine.plaza2._
import AkkaIntegrationConfigurations._
import scheme.FutInfo.{Signs, SessContentsRecord}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import com.ergodicity.engine.core.model.FutureContract
import scheme.{OptTrade, Deserializer}
import java.util.concurrent.{CountDownLatch, TimeUnit}

class OptTradeDataStreamIntegrationSpec extends TestKit(ActorSystem("OptTradeDataStreamIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec {
  val log = LoggerFactory.getLogger(classOf[FutInfoDataStreamIntegrationSpec])


  val Host = "localhost"
  val Port = 4001
  val AppName = "OptTradeDataStreamIntegrationSpec"

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

      val ini = new File("core/scheme/OptTrade.ini")
      val tableSet = TableSet(ini)
      val underlyingStream = P2DataStream("FORTS_OPTTRADE_REPL", CombinedDynamic, tableSet)

      val dataStream = TestFSMRef(new DataStream(underlyingStream), "FORTS_OPTTRADE_REPL")
      dataStream ! SetLifeNumToIni(ini)

      val ordersLogRepo = TestFSMRef(new Repository[OptTrade.OrdersLogRecord], "OrdersLogRepo")
      val dealsRepo = TestFSMRef(new Repository[OptTrade.DealRecord], "DealsRepo")

      dataStream ! JoinTable(ordersLogRepo, "orders_log", implicitly[Deserializer[OptTrade.OrdersLogRecord]])
      dataStream ! JoinTable(dealsRepo, "deal", implicitly[Deserializer[OptTrade.DealRecord]])
      dataStream ! Open(underlyingConnection)

      val latch = new CountDownLatch(2)

      ordersLogRepo ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot: Snapshot[OptTrade.OrdersLogRecord] =>
            log.info("Got snapshot; size = " + snapshot.data.size)
            snapshot.data foreach {
              rec =>
                log.info("Record: " + rec)
            }
            latch.countDown()
            context.stop(self)
        }
      }))

      dealsRepo ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot: Snapshot[OptTrade.DealRecord] =>
            log.info("Got snapshot; size = " + snapshot.data.size)
            snapshot.data foreach {
              rec =>
                log.info("Record: " + rec)
            }
            latch.countDown()
            context.stop(self)
        }
      }))


      latch.await(10, TimeUnit.SECONDS)
    }
  }
}