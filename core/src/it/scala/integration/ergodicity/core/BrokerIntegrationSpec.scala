package integration.ergodicity.core

import java.io.File
import plaza2.RouterStatus.RouterConnected
import plaza2.{MessageFactory, Connection}
import com.ergodicity.core.common.{FullIsin, FutureContract}
import com.ergodicity.core.common.OrderType._
import com.ergodicity.core.AkkaConfigurations
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.pattern.ask
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.core.broker.BrokerCommand.{Cancel, Sell}
import com.ergodicity.core.broker.{CancelReport, ExecutionReport, FutOrder, Broker}
import akka.util.Timeout
import akka.dispatch.Await

class BrokerIntegrationSpec extends TestKit(ActorSystem("BrokerIntegrationSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val Host = "localhost"
  val Port = 4001
  val AppName = "BrokerIntegrationSpec"

  import akka.util.duration._
  implicit val timeout = Timeout(5 seconds)
  implicit val factory = MessageFactory(new File("core/scheme/p2fortsgate_messages.ini"))

  "Broker" must {
    "should failt to buy wrong future contract" in {
      val conn = Connection()

      conn.host = Host
      conn.port = Port
      conn.appName = AppName
      conn.connect()

      log.info("Status: " + conn.status)
      log.info("Router status: " + conn.routerStatus)

      assert(conn.status == plaza2.ConnectionStatus.ConnectionConnected)
      assert(conn.routerStatus == Some(RouterConnected))

      val broker = TestActorRef(new Broker("533", conn))

      val future = FutureContract(FullIsin(0, "RTS-9.12", ""), "")

      val report = Await.result((broker ? Sell(future, GoodTillCancelled, BigDecimal(127000), 3)).mapTo[ExecutionReport[FutOrder]], 2 seconds)

      log.info("Report = " + report)

      if (report.order.isRight) {
        val order = report.order.right.get
        val cancel = Await.result((broker ? Cancel(order)).mapTo[CancelReport], 2 seconds)
        log.info("Cancel = " + cancel)
      }

      conn.disconnect()
    }

    "cancel order" in {
      val order = FutOrder(2816543292l)

      val conn = Connection()

      conn.host = Host
      conn.port = Port
      conn.appName = AppName
      conn.connect()

      log.info("Status: " + conn.status)
      log.info("Router status: " + conn.routerStatus)

      assert(conn.status == plaza2.ConnectionStatus.ConnectionConnected)
      assert(conn.routerStatus == Some(RouterConnected))

      val broker = TestActorRef(new Broker("533", conn))

      val cancel = Await.result((broker ? Cancel(order)).mapTo[CancelReport], 2 seconds)
      log.info("Cancel = " + cancel)

      conn.disconnect()
    }
  }
}
