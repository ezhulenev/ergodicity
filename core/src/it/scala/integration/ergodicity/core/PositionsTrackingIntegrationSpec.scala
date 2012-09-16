package integration.ergodicity.core

import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.{Props, Actor, ActorSystem}
import akka.event.Logging
import akka.pattern.ask
import akka.pattern.pipe
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate._
import com.ergodicity.core.SessionsTracking.{OngoingSessionTransition, OngoingSession, SubscribeOngoingSessions}
import com.ergodicity.core.session.SessionActor.GetAssignedContents
import com.ergodicity.core.{SessionsTracking, PositionsTracking}
import config.ConnectionConfig.Tcp
import config.Replication.{ReplicationParams, ReplicationMode}
import config.{Replication, CGateConfig}
import integration.ergodicity.core.AkkaIntegrationConfigurations._
import java.io.File
import java.util.concurrent.TimeUnit
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{Connection => CGConnection, Listener => CGListener, P2TypeParser, CGate}
import scala.Some

class PositionsTrackingIntegrationSpec extends TestKit(ActorSystem("PositionsTrackingIntegrationSpec", ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  val Host = "localhost"
  val Port = 4001

  val RouterConnection = Tcp(Host, Port, system.name)

  val ReplicationDispatcher = "capture.dispatchers.replicationDispatcher"

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
    P2TypeParser.setCharset("windows-1251")
  }

  override def afterAll() {
    system.shutdown()
    CGate.close()
  }

  implicit val timeout = Timeout(5.seconds)

  "Positions Tracking" must {
    "should work" in {
      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = system.actorOf(Props(new Connection(underlyingConnection, Some(500.millis))).withDispatcher(ReplicationDispatcher), "Connection")

      val dataStream = system.actorOf(Props(new DataStream), "PositionsDataStream")

      val FutInfoDataStream = system.actorOf(Props(new DataStream), "FutInfoDataStream")
      val OptInfoDataStream = system.actorOf(Props(new DataStream), "OptInfoDataStream")

      // Listeners
      val futInfoListenerConfig = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/FutInfo.ini"), "CustReplScheme")
      val underlyingFutInfoListener = new CGListener(underlyingConnection, futInfoListenerConfig(), new DataStreamSubscriber(FutInfoDataStream))
      val futInfoListener = system.actorOf(Props(new Listener(underlyingFutInfoListener)).withDispatcher(ReplicationDispatcher), "FutInfoListener")

      val optInfoListenerConfig = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/OptInfo.ini"), "CustReplScheme")
      val underlyingOptInfoListener = new CGListener(underlyingConnection, optInfoListenerConfig(), new DataStreamSubscriber(OptInfoDataStream))
      val optInfoListener = system.actorOf(Props(new Listener(underlyingOptInfoListener)).withDispatcher(ReplicationDispatcher), "OptInfoListener")

      val sessions = system.actorOf(Props(new SessionsTracking(FutInfoDataStream, OptInfoDataStream)), "SessionsTracking")

      // Position Listener
      val listenerConfig = Replication("FORTS_POS_REPL", new File("cgate/scheme/Pos.ini"), "CustReplScheme")
      val underlyingListener = new CGListener(underlyingConnection, listenerConfig(), new DataStreamSubscriber(dataStream))
      val listener = system.actorOf(Props(new Listener(underlyingListener)).withDispatcher(ReplicationDispatcher), "PosListener")

      // Create Positions actor
      val positions = system.actorOf(Props(new PositionsTracking(dataStream)), "Positions")
      sessions ! SubscribeOngoingSessions(system.actorOf(Props(new Actor {
        var openedPosition = false

        protected def receive = {
          case OngoingSession(id, ref) =>
            (ref ? GetAssignedContents) pipeTo positions
            if (!openedPosition) {
              system.scheduler.scheduleOnce(10.second) {
                listener ! Listener.Open(ReplicationParams(ReplicationMode.Combined))
              }
              openedPosition = true
            }

          case OngoingSessionTransition(_, OngoingSession(id, ref)) =>
            (ref ? GetAssignedContents) pipeTo positions
        }
      })))

      Thread.sleep(1000)

      // On connection Activated open listeners etc
      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) =>
            // Open Listeners
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