package integration.ergodicity.core

import org.slf4j.LoggerFactory
import java.io.File
import org.scalatest.WordSpec
import plaza2.RequestType.CombinedDynamic
import plaza2.{TableSet, Connection => P2Connection, DataStream => P2DataStream}
import akka.actor.{Actor, Props, ActorSystem}
import com.ergodicity.plaza2.DataStream.{BindTable, SetLifeNumToIni, Open}
import com.ergodicity.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import com.ergodicity.plaza2.Connection.{ProcessMessages, Connect}
import com.ergodicity.plaza2._
import AkkaIntegrationConfigurations._
import scheme.FutInfo.{Signs, SessContentsRecord}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import com.ergodicity.core.common.FutureContract
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scheme.{OptTrade, FutTrade, Deserializer}
import com.ergodicity.core.order.FutureOrders
import com.ergodicity.core.order.FutureOrders.BindFutTradeRepl

class FutureOrdersIntegrationSpec extends TestKit(ActorSystem("FutureOrdersIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec {
  val log = LoggerFactory.getLogger(classOf[FutTradeDataStreamIntegrationSpec])


  val Host = "localhost"
  val Port = 4001
  val AppName = "FutureOrdersIntegrationSpec"

  "Future Orders" must {
    "handle orders from FORTS_FUTTRADE_REPL" in {

      val underlyingConnection = P2Connection()
      val connection = system.actorOf(Props(Connection(underlyingConnection)), "Connection")
      connection ! Connect(Host, Port, AppName)

      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, ConnectionState.Connected) => connection ! ProcessMessages(100);
        }
      })))

      // Create data stream
      val ini = new File("core/scheme/FutTrade.ini")
      val tableSet = TableSet(ini)
      val underlyingStream = P2DataStream("FORTS_FUTTRADE_REPL", CombinedDynamic, tableSet)

      val dataStream = TestFSMRef(new DataStream(underlyingStream), "FORTS_FUTTRADE_REPL")
      dataStream ! SetLifeNumToIni(ini)

      // Construct FutureOrders
      val futureOrders = TestFSMRef(new FutureOrders, "FutureOrders")
      futureOrders ! BindFutTradeRepl(dataStream)

      // Open data stream
      dataStream ! Open(underlyingConnection)

      Thread.sleep(TimeUnit.DAYS.toMillis(1))
    }
  }
}