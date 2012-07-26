package com.ergodicity.capture

import org.slf4j.LoggerFactory
import akka.actor._
import com.twitter.ostrich.admin.{RuntimeEnvironment, Service}
import com.typesafe.config.ConfigFactory
import java.util.concurrent.TimeUnit
import com.ergodicity.cgate.config.{CGateConfig, ConnectionConfig}
import ru.micexrts.cgate.{CGate, Connection => CGConnection}

object CaptureEngine {
  val log = LoggerFactory.getLogger(getClass.getName)

  var marketCapture: CaptureEngine = null
  var runtime: RuntimeEnvironment = null

  def main(args: Array[String]) {
    try {
      runtime = RuntimeEnvironment(this, args)
      marketCapture = runtime.loadRuntimeConfig[CaptureEngine]()
      marketCapture.start()

      // Add shutdown hook
      Runtime.getRuntime.addShutdownHook(new Thread() {
        override def run() {
          marketCapture.marketCapture ! ShutDown
          while (!marketCapture.system.isTerminated) {
            Thread.sleep(TimeUnit.SECONDS.toMillis(1))
          }
        }
      });

    } catch {
      case e =>
        log.error("Exception during startup; exiting!", e)
        System.exit(1)
    }
  }
}

class CaptureEngine(cgateConfig: CGateConfig, connectionConfig: ConnectionConfig, replication: ReplicationScheme, database: CaptureDatabase, kestrel: KestrelConfig) extends Service {
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

    // Prepare CGate
    CGate.open(cgateConfig())

    // Create Market Capture system
    val connection = new CGConnection(connectionConfig())
    val repo = new MarketCaptureRepository(database) with ReplicationStateRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository
    marketCapture = system.actorOf(Props(new MarketCapture(connection, replication, repo, kestrel)), "MarketCapture")

    // Let all actors to activate and perform all activities
    Thread.sleep(TimeUnit.SECONDS.toMillis(5))

    // Watch for Market Capture is working
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
    CGate.close()
    System.exit(1)
  }
}