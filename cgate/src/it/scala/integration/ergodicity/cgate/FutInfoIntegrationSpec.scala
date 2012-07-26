package integration.ergodicity.cgate

import java.io.File
import integration._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{CGate, Connection => CGConnection, Listener => CGListener}
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import akka.actor.{Actor, Props, ActorSystem}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate._
import config.{Replication, CGateConfig}
import repository.Repository
import repository.Repository.{Snapshot, SubscribeSnapshots}
import scheme.FutInfo
import com.ergodicity.cgate.config.Replication._
import akka.testkit.{TestActorRef, TestFSMRef, ImplicitSender, TestKit}
import akka.event.Logging
import java.util.concurrent.TimeUnit
import com.ergodicity.cgate.Protocol._
import com.ergodicity.cgate.repository.ReplicaExtractor._
import com.ergodicity.cgate.DataStream.BindTable


class FutInfoIntegrationSpec extends TestKit(ActorSystem("FutInfoIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
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

  "FutInfo DataStream" must {
    "load contents to Reportitory" in {
      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = TestFSMRef(new Connection(underlyingConnection), "Connection")
      connection ! Connection.Open

      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) => connection ! StartMessageProcessing(100);
        }
      })))

      val FutInfoDataStream = TestFSMRef(new DataStream, "FutInfoDataStream")

      // Listener
      val listenerConfig = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/fut_info.ini"), "CustReplScheme")
      val underlyingListener = new CGListener(underlyingConnection, listenerConfig(), new DataStreamSubscriber(FutInfoDataStream))
      val listener = TestFSMRef(new Listener(underlyingListener), "Listener")

      // Repository
      val sessionsRepository = TestFSMRef(Repository[FutInfo.session], "SessionsRepository")
      FutInfoDataStream ! BindTable(FutInfo.session.TABLE_INDEX, sessionsRepository)

      val futuresRepository = TestFSMRef(Repository[FutInfo.fut_sess_contents], "FuturesRepository")
      FutInfoDataStream ! BindTable(FutInfo.fut_sess_contents.TABLE_INDEX, futuresRepository)

      // Open Listener in Combined mode
      listener ! Listener.Open(ReplicationParams(ReplicationMode.Combined))

      // Handle repository data
      sessionsRepository ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot: Snapshot[FutInfo.session] =>
            log.info("Got Sessions snapshot, size = " + snapshot.data.size)
            snapshot.data foreach {
              rec =>
                log.info("SessionRecord: " + rec)
            }
        }
      }))

      futuresRepository ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot: Snapshot[FutInfo.session] =>
            log.info("Got Futures Contents snapshot, size = " + snapshot.data.size)
            snapshot.data foreach {
              rec =>
                log.info("ContentsRecord: " + rec)
            }
        }
      }))

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }


}