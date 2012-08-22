package integration.ergodicity.engine

import java.io.File
import java.util.concurrent.TimeUnit
import akka.actor.{Actor, Props, ActorSystem}
import akka.util.duration._
import integration.ergodicity.core.AkkaIntegrationConfigurations._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{ImplicitSender, TestKit}
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate._
import config.ConnectionConfig.Tcp
import config.Replication.{ReplicationParams, ReplicationMode}
import config.{Replication, CGateConfig}
import ru.micexrts.cgate.{P2TypeParser, CGate, Connection => CGConnection, Listener => CGListener}
import akka.actor.FSM.Transition
import scala.Some
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.engine.service.SessionsTracking
import com.ergodicity.engine.service.SessionsTracking.SubscribeOngoingSessions


class SessionsTrackingIntegrationSpec extends TestKit(ActorSystem("SessionsTrackingIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  val Host = "localhost"
  val Port = 4001

  val RouterConnection = Tcp(Host, Port, system.name)

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
    P2TypeParser.setCharset("windows-1251")
  }

  override def afterAll() {
    system.shutdown()
    CGate.close()
  }

  "SessionsTracking" must {
    "should work" in {
      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = system.actorOf(Props(new Connection(underlyingConnection, Some(500.millis))), "Connection")

      val FutInfoDataStream = system.actorOf(Props(new DataStream), "FutInfoDataStream")
      val OptInfoDataStream = system.actorOf(Props(new DataStream), "OptInfoDataStream")

      // Listeners
      val futInfoListenerConfig = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/fut_info.ini"), "CustReplScheme")
      val underlyingFutInfoListener = new CGListener(underlyingConnection, futInfoListenerConfig(), new DataStreamSubscriber(FutInfoDataStream))
      val futInfoListener = system.actorOf(Props(new Listener(underlyingFutInfoListener)), "FutInfoListener")

      val optInfoListenerConfig = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/opt_info.ini"), "CustReplScheme")
      val underlyingOptInfoListener = new CGListener(underlyingConnection, optInfoListenerConfig(), new DataStreamSubscriber(OptInfoDataStream))
      val optInfoListener = system.actorOf(Props(new Listener(underlyingOptInfoListener)), "OptInfoListener")

      val sessions = system.actorOf(Props(new SessionsTracking(FutInfoDataStream, OptInfoDataStream)), "SessionsTracking")

      sessions ! SubscribeOngoingSessions(system.actorOf(Props(new Actor {
        protected def receive = {
          case e => log.info("ONGOING SESSION EVENT = " + e)
        }
      })))

      Thread.sleep(1000)

      // On connection Activated open listeners etc
      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) =>
            // Open Listeners in Combined mode
            futInfoListener ! Listener.Open(ReplicationParams(ReplicationMode.Combined))
            optInfoListener ! Listener.Open(ReplicationParams(ReplicationMode.Combined))

            // Process messages
            connection ! StartMessageProcessing(500.millis)
        }
      })))

      // Open connections and track it's status
      connection ! Connection.Open

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}