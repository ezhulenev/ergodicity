package com.ergodicity.capture

import org.slf4j.LoggerFactory
import akka.actor._
import akka.util.duration._
import com.twitter.ostrich.admin.{ServiceTracker, RuntimeEnvironment, Service}
import java.util.concurrent.TimeUnit
import com.ergodicity.cgate.config.ConnectionConfig
import ru.micexrts.cgate.{Connection => CGConnection, P2TypeParser, CGate}
import com.ergodicity.capture.MarketCapture.{ShutDown, Capture}
import akka.actor.FSM.Failure
import com.ergodicity.cgate.config.CGateConfig
import akka.actor.Terminated
import akka.actor.SupervisorStrategy.Stop

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
      case e: Throwable =>
        log.error("Exception during startup; exiting!", e)
        System.exit(1)
    }
  }
}

class CaptureEngine(cgateConfig: CGateConfig, connectionConfig: ConnectionConfig, scheme: ReplicationScheme, database: CaptureDatabase, kestrel: KestrelConfig) extends Service {
  val log = LoggerFactory.getLogger(classOf[CaptureEngine])

  implicit val system = ActorSystem("CaptureEngine")

  ServiceTracker.register(this)

  private val repo = new MarketCaptureRepository(database) with ReplicationStateRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository

  private def conn = new CGConnection(connectionConfig())

  def newMarketCaptureInstance = new MarketCapture(scheme, repo, kestrel) with CaptureConnection with UnderlyingListenersImpl with CaptureListenersImpl {
    def underlyingConnection = conn
  }

  var guardian: ActorRef = system.deadLetters

  def start() {
    log.info("Start CaptureEngine")

    // Prepare CGate
    CGate.open(cgateConfig())
    P2TypeParser.setCharset("windows-1251")

    // Watch for Market Capture is working
    guardian = system.actorOf(Props(new Guardian(this)), "CaptureGuardian")

    // Let all actors to activate and perform all activities
    Thread.sleep(TimeUnit.SECONDS.toMillis(1))

    // Schedule periodic restarting
    system.scheduler.schedule(2.minutes, 2.minutes, guardian, Restart)

    guardian ! Capture
  }

  def shutdown() {
    guardian ! ShutDown
  }

  case object Restart

}

sealed trait GuardianState

case object Working extends GuardianState

case object Restarting extends GuardianState

class Guardian(engine: CaptureEngine) extends Actor with FSM[GuardianState, ActorRef] {

  import engine._

  // Supervisor
  override val supervisorStrategy = AllForOneStrategy() {
    case _: MarketCaptureException => Stop
  }

  // Create Market Capture system
  var marketCapture = context.actorOf(Props(newMarketCaptureInstance), "MarketCapture")
  context.watch(marketCapture)

  startWith(Working, marketCapture)

  when(Working) {
    case Event(Terminated(ref), mc) if (ref == mc) =>
      system.shutdown()
      System.exit(-1)
      stop(Failure("Market Capture unexpected terminated"))

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

  whenUnhandled {
    case Event(e@(Capture | ShutDown), capture) =>
      capture ! e
      stay()
  }

}