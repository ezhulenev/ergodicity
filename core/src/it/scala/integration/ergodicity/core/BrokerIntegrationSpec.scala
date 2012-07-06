package integration.ergodicity.core

import java.io.File
import plaza2.RouterStatus.RouterConnected
import plaza2.{MessageFactory, Connection}
import com.ergodicity.core.broker.{FutOrder, Broker}
import com.ergodicity.core.common.{GoodTillCancelled, Isin, FutureContract}
import com.ergodicity.core.AkkaConfigurations
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.ActorSystem

class BrokerIntegrationSpec extends TestKit(ActorSystem("BrokerIntegrationSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val Host = "localhost"
  val Port = 4001
  val AppName = "BrokerIntegrationSpec"

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

      val broker = new Broker("533", conn)

      val future = FutureContract(Isin(0, "RTS-9.12", ""), "")

      val order = broker.sell(future, GoodTillCancelled, BigDecimal(127000), 3)

      log.info("Order = " + order)

      if (order.isRight) {
        val cancel = broker.cancel(order.right.get)
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

      val broker = new Broker("533", conn)

      val cancel = broker.cancel(order)
      log.info("Cancel = " + cancel)

      conn.disconnect()
    }
  }
}
