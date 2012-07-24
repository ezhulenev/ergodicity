package com.ergodicity.capture

import org.slf4j.LoggerFactory
import akka.actor._
import com.twitter.ostrich.admin.{RuntimeEnvironment, Service}
import com.typesafe.config.ConfigFactory
import ru.micexrts.cgate.{Connection => CGConnection}
import com.ergodicity.cgate.config.ConnectionType

object CaptureEngine {
  val log = LoggerFactory.getLogger(getClass.getName)

  var marketCapture: CaptureEngine = null
  var runtime: RuntimeEnvironment = null

  def main(args: Array[String]) {
    try {
      runtime = RuntimeEnvironment(this, args)
      marketCapture = runtime.loadRuntimeConfig[CaptureEngine]()
      marketCapture.start()
    } catch {
      case e =>
        log.error("Exception during startup; exiting!", e)
        System.exit(1)
    }
  }
}

class CaptureEngine(connectionType: ConnectionType, replication: ReplicationScheme, database: CaptureDatabase, kestrel: KestrelConfig) extends Service {
  val log = LoggerFactory.getLogger(classOf[CaptureEngine])

  val ConfigWithDetailedLogging = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.actor.debug {
      receive = on
      lifecycle = on
    }
    """)

  implicit val system = ActorSystem("CaptureEngine", ConfigWithDetailedLogging)

  var marketCapture: ActorRef = _

  def start() {
    log.info("Start CaptureEngine")

    val connection = new CGConnection(connectionType())
    val repo = new MarketCaptureRepository(database) with ReplicationStateRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository
    marketCapture = system.actorOf(Props(new MarketCapture(connection, replication, repo, kestrel)), "MarketCapture")

    system.actorOf(Props(new Actor {
      context.watch(marketCapture)

      protected def receive = {
        case Terminated(ref) if (ref == marketCapture) => shutdown();
      }
    }), "Watcher")

    marketCapture ! Capture
  }

  def shutdown() {
    log.info("Shutdown CaptureEngine")
    system.shutdown()
    Thread.sleep(1000)
    System.exit(1)
  }
}