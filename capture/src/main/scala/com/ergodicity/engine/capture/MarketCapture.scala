package com.ergodicity.engine.capture

import org.slf4j.LoggerFactory
import akka.actor._
import akka.util.duration._
import com.twitter.ostrich.admin.{RuntimeEnvironment, ServiceTracker, Service}

case class ConnectionProperties(host: String, port: Int, appName: String)

object MarketCapture {
  val log = LoggerFactory.getLogger(getClass.getName)

  var marketCapture: MarketCapture = null
  var runtime: RuntimeEnvironment = null

  def main(args: Array[String]) {
    try {
      runtime = RuntimeEnvironment(this, args)
      marketCapture = runtime.loadRuntimeConfig[MarketCapture]()
      marketCapture.start()
    } catch {
      case e =>
        log.error("Exception during startup; exiting!", e)
        System.exit(1)
    }
    log.info("Return from main!!!")
  }
}

class MarketCapture(connectionProperties: ConnectionProperties) extends Service {
  val log = LoggerFactory.getLogger(classOf[MarketCapture])

  implicit val system = ActorSystem("MarketCapture")

  def start() {
    log.info("Start MarketCapture")

    system.scheduler.schedule(10.seconds, 10.seconds) {
      ServiceTracker.shutdown()
      System.exit(1)
    }
  }

  def shutdown() {
    log.info("Shutdown MarketCapture")

  }
}