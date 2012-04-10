package com.ergodicity.engine.plaza2

import org.scalatest.Spec
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.twitter.util.FuturePool
import java.util.concurrent.{TimeUnit, Executors}
import plaza2.RequestType.CombinedDynamic
import plaza2.{TableSet, StreamDataDeleted, StreamDataInserted, Connection => P2Connection, DataStream => P2DataStream}

class BaselessClientIntegrationSpec extends Spec {
  val log = LoggerFactory.getLogger(classOf[BaselessClientIntegrationSpec])

  val Pool = FuturePool(Executors.newCachedThreadPool())

  describe("BaselessClient") {
    it("should receive streams: AggregatesId & TradesId") {
      val client = new BaselessClient

      Pool(client.run());

      Thread.sleep(TimeUnit.DAYS.toMillis(10))

      client.stop()

      Thread.sleep(TimeUnit.SECONDS.toMillis(1))
    }
  }

}

class BaselessClient {
  val log = LoggerFactory.getLogger(classOf[BaselessClient])

  val _stop = new AtomicBoolean(false)

  val Host = "localhost"
  val Port = 4001
  val AppName = "BaselessClient"


  // Initialize connection
  val connection = P2Connection()

  val dataStream = P2DataStream("FORTS_FUTINFO_REPL", CombinedDynamic, TableSet(new File("plaza2/scheme/FuturesSession.ini")))

  def run() {
    import com.ergodicity.engine.plaza2.protocol.FutInfo._
    log.info("Start client")

    connection.host = Host
    connection.port = Port
    connection.appName = AppName
    connection.dispatchEvents {
      status => log.info("Connection status updated: " + status)
    }
    connection.connect()

    dataStream.dispatchEvents {
      case StreamDataInserted("session", record) =>
        val sessRec = SessionDeserializer(record)
        log.info("StreamDataInserted: "+sessRec)
      case StreamDataDeleted("session", id, record) =>
        val sessRec = SessionDeserializer(record)
        log.info("StreamDataDeleted: "+sessRec)
      case event => log.info("Trades event: " + event)
    }
    dataStream.open(connection)

    while (!_stop.get) {
      log.info("Process Messages")
      connection.processMessage(100000)
    }

    log.info("Close client")

    //closeAggregates()
    dataStream.close()
    connection.disconnect()
  }

  def stop() {
    _stop.set(true)
  }

}