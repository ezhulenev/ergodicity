package integration.ergodicity.core

import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.{Actor, Props, ActorSystem}
import akka.event.Logging
import akka.pattern.ask
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate._
import com.ergodicity.core.{FutureContract, SessionsTracking}
import com.ergodicity.core.SessionsTracking.{OngoingSession, SubscribeOngoingSessions}
import com.ergodicity.core.session.SessionActor.{AssignedInstruments, GetAssignedInstruments}
import config.ConnectionConfig.Tcp
import config.Replication.{ReplicationParams, ReplicationMode}
import config.{Replication, CGateConfig}
import integration.ergodicity.core.AkkaIntegrationConfigurations._
import java.io.File
import java.util.concurrent.TimeUnit
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{P2TypeParser, CGate, Connection => CGConnection, Listener => CGListener}
import scala.Some
import akka.dispatch.Await
import com.ergodicity.core.session.Instrument


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

  implicit val timeout = Timeout(1.second)

  "SessionsTracking" must {
    "should work" in {
      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = system.actorOf(Props(new Connection(underlyingConnection, Some(500.millis))), "Connection")

      val FutInfoDataStream = system.actorOf(Props(new DataStream), "FutInfoDataStream")
      val OptInfoDataStream = system.actorOf(Props(new DataStream), "OptInfoDataStream")

      // Listeners
      val futInfoListenerConfig = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/FutInfo.ini"), "CustReplScheme")
      val underlyingFutInfoListener = new CGListener(underlyingConnection, futInfoListenerConfig(), new DataStreamSubscriber(FutInfoDataStream))
      val futInfoListener = system.actorOf(Props(new Listener(underlyingFutInfoListener)), "FutInfoListener")

      val optInfoListenerConfig = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/OptInfo.ini"), "CustReplScheme")
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

      Thread.sleep(TimeUnit.SECONDS.toMillis(10))

      sessions ! SubscribeOngoingSessions(TestActorRef(new Actor {
        protected def receive = {
          case OngoingSession(Some((id, ref))) =>
            log.info("Ongoing session = " + id)
            val assigned = Await.result((ref ? GetAssignedInstruments).mapTo[AssignedInstruments], 1.second)
            log.info("Assigned instruments; Size = " + assigned.instruments.size)
            assigned.instruments foreach {
              case instrument@Instrument(_: FutureContract, _) =>
                log.info("Future contract = " + instrument)
              case _ =>
            }
        }
      }))

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}