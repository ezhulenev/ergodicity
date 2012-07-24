package integration.ergodicity.core

import java.io.File
import java.util.concurrent.TimeUnit
import akka.actor.{Actor, Props, ActorSystem}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import integration.ergodicity.core.AkkaIntegrationConfigurations._
import com.ergodicity.core.Sessions
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import com.ergodicity.cgate.config.ConnectionType.Tcp
import ru.micexrts.cgate.{CGate, Connection => CGConnection, Listener => CGListener}
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate.config.Replication._
import com.ergodicity.cgate._
import config.{Replication, CGateConfig}


class SessionsIntegrationSpec extends TestKit(ActorSystem("SessionsIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  val Host = "localhost"
  val Port = 4001

  val RouterConnection = Tcp(Host, Port, system.name)

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
  }

  override def afterAll() {
    system.shutdown()
    CGate.close()
  }

  "Sessions" must {
    "should work" in {
      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = TestFSMRef(new Connection(underlyingConnection), "Connection")
      connection ! Connection.Open

      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) => connection ! StartMessageProcessing(100);
        }
      })))


      val FutInfoDataStream = TestFSMRef(new DataStream, "FutInfoDataStream")
      val OptInfoDataStream = TestFSMRef(new DataStream, "OptInfoDataStream")

      // Listeners
      val futInfoListenerConfig = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/fut_info.ini"), "CustReplScheme")
      val underlyingFutInfoListener = new CGListener(underlyingConnection, futInfoListenerConfig(), new DataStreamSubscriber(FutInfoDataStream))
      val futInfoListener = TestFSMRef(new Listener(underlyingFutInfoListener), "FutInfoListener")

      val optInfoListenerConfig = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/opt_info.ini"), "CustReplScheme")
      val underlyingOptInfoListener = new CGListener(underlyingConnection, optInfoListenerConfig(), new DataStreamSubscriber(OptInfoDataStream))
      val optInfoListener = TestFSMRef(new Listener(underlyingOptInfoListener), "OptInfoListener")

      val sessions = TestActorRef(new Sessions(FutInfoDataStream, OptInfoDataStream), "Sessions")
      sessions ! Sessions.BindSessions

      Thread.sleep(1000)

      // Open Listener in Combined mode
      futInfoListener ! Listener.Open(ReplicationParams(ReplicationMode.Combined))
      optInfoListener ! Listener.Open(ReplicationParams(ReplicationMode.Combined))

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}