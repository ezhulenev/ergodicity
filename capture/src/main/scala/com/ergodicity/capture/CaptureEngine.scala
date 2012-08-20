package com.ergodicity.capture

import org.slf4j.LoggerFactory
import akka.actor._
import akka.util.duration._
import com.twitter.ostrich.admin.{ServiceTracker, RuntimeEnvironment, Service}
import com.typesafe.config.ConfigFactory
import java.util.concurrent.TimeUnit
import com.ergodicity.cgate.config.{CGateConfig, ConnectionConfig}
import ru.micexrts.cgate.{P2TypeParser, CGate, Connection => CGConnection}
import com.ergodicity.capture.MarketCapture.{ShutDown, Capture}
import akka.actor.FSM.Failure

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

class CaptureEngine(cgateConfig: CGateConfig, connectionConfig: ConnectionConfig, scheme: ReplicationScheme, database: CaptureDatabase, kestrel: KestrelConfig) extends Service {
  val log = LoggerFactory.getLogger(classOf[CaptureEngine])

  implicit val system = ActorSystem("CaptureEngine")

  var marketCapture: ActorRef = _

  ServiceTracker.register(this)

  private val repo = new MarketCaptureRepository(database) with ReplicationStateRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository
  private def conn = new CGConnection(connectionConfig())

  def newMarketCaptureInstance = new MarketCapture(scheme, repo, kestrel) with CaptureConnection with CaptureListenersImpl {
    def underlyingConnection = conn
  }

  def start() {
    log.info("Start CaptureEngine")

    // Prepare CGate
    CGate.open(cgateConfig())
    P2TypeParser.setCharset("windows-1251")

    // Create Market Capture system
    marketCapture = system.actorOf(Props(newMarketCaptureInstance), "MarketCapture")

    // Let all actors to activate and perform all activities
    Thread.sleep(TimeUnit.DAYS.toMillis(1))

    // Watch for Market Capture is working
    val guardian = system.actorOf(Props(new Actor with FSM[GuardianState, ActorRef] {
      context.watch(marketCapture)

      startWith(Working, marketCapture)

      when(Working) {
        case Event(Terminated(ref), mc) if (ref == mc) =>
          system.shutdown()
          System.exit(-1)
          stop(Failure("Market Capture terminated"))

        case Event(Restart, mc) =>
          log.info("Restart Market Capture")
          mc ! ShutDown
          goto(Restarting)
      }

      when(Restarting) {
        case Event(Terminated(ref), mc) if (ref == mc) =>
          // Let connection to be closed
          Thread.sleep(TimeUnit.SECONDS.toMillis(3))

          // Create new Market Capture system
          marketCapture = system.actorOf(Props(newMarketCaptureInstance), "MarketCapture")
          context.watch(marketCapture)

          // Wait for capture initialized
          Thread.sleep(TimeUnit.SECONDS.toMillis(1))

          // Start capturing
          marketCapture ! Capture
          goto(Working) using (marketCapture)
      }

    }), "CaptureGuardian")

    // Schedule periodic restarting
    system.scheduler.schedule(2.minutes, 2.minutes, guardian, Restart)

    marketCapture ! Capture
  }

  def shutdown() {
    marketCapture ! ShutDown
  }

  case object Restart

  sealed trait GuardianState
  case object Working extends GuardianState
  case object Restarting extends GuardianState
}

