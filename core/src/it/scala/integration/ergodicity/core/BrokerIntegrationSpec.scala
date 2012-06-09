package integration.ergodicity.core

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory
import java.io.File
import plaza2.RouterStatus.RouterConnected
import plaza2.{MessageFactory, Connection}
import com.ergodicity.core.broker.{FutOrder, GoodTillCancelled, Broker}
import com.ergodicity.core.common.{Isin, FutureContract}


class BrokerIntegrationSpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[BrokerIntegrationSpec])

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

      // RTS-6.12,RIM2,159336,Фьючерсный контракт RTS-6.12
      val future = FutureContract(Isin(0, "RTS-6.12", ""), "")

      val order = broker.buy(future, GoodTillCancelled, BigDecimal(121000), 1)

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
