package integration.ergodicity.cgate

import java.io.File
import integration._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import akka.actor.{Actor, Props, ActorSystem}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import akka.util.duration._
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
import ru.micexrts.cgate.{P2TypeParser, CGate, Connection => CGConnection, Listener => CGListener}


class FutInfoIntegrationSpec extends TestKit(ActorSystem("FutInfoIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
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

  private[this] sealed trait SessionState

  private[this] object SessionState {
    def apply(state: Int) = state match {
      case 0 => Assigned
      case 1 => Online
      case 2 => Suspended
      case 3 => Canceled
      case 4 => Completed
    }

    case object Assigned extends SessionState

    case object Online extends SessionState

    case object Suspended extends SessionState

    case object Canceled extends SessionState

    case object Completed extends SessionState

  }

  private[this] sealed trait InstrumentState

  private[this] object InstrumentState {

    def apply(sessionState: SessionState) = sessionState match {
      case SessionState.Assigned => Assigned
      case SessionState.Online => Online
      case SessionState.Suspended => Suspended
      case SessionState.Canceled => Canceled
      case SessionState.Completed => Completed
    }

    def apply(state: Long) = state match {
      case 0 => Assigned
      case 1 => Online
      case 2 => Suspended
      case 3 => Canceled
      case 4 => Completed
      case 5 => Suspended
    }

    case object Assigned extends InstrumentState

    case object Online extends InstrumentState

    case object Canceled extends InstrumentState

    case object Completed extends InstrumentState

    case object Suspended extends InstrumentState

  }


  "FutInfo DataStream" must {
    "load contents to Reportitory" in {
      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = TestFSMRef(new Connection(underlyingConnection), "Connection")

      val FutInfoDataStream = TestFSMRef(new DataStream, "FutInfoDataStream")

      // Listener
      val listenerConfig = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/fut_info.ini"), "CustReplScheme")
      val underlyingListener = new CGListener(underlyingConnection, listenerConfig(), new DataStreamSubscriber(FutInfoDataStream))
      val listener = TestFSMRef(new Listener(BindListener(underlyingListener) to connection), "Listener")

      // Repository
      val sessionsRepository = TestFSMRef(Repository[FutInfo.session], "SessionsRepository")
      FutInfoDataStream ! BindTable(FutInfo.session.TABLE_INDEX, sessionsRepository)

      val futuresRepository = TestFSMRef(Repository[FutInfo.fut_sess_contents], "FuturesRepository")
      FutInfoDataStream ! BindTable(FutInfo.fut_sess_contents.TABLE_INDEX, futuresRepository)

      val sysEventsRepository = TestFSMRef(Repository[FutInfo.sys_events], "SysEventsRepository")
      FutInfoDataStream ! BindTable(FutInfo.sys_events.TABLE_INDEX, sysEventsRepository)

      // Handle repository data
      sessionsRepository ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot: Snapshot[FutInfo.session] =>
            log.info("Got Sessions snapshot, size = " + snapshot.data.size)
            snapshot.data foreach {
              rec =>
                log.info("SessionRecord; Session id = " + rec.get_sess_id() + ", option session id = " + rec.get_opt_sess_id() + ", state = " + SessionState(rec.get_state()))
            }
        }
      }))

      futuresRepository ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot: Snapshot[FutInfo.fut_sess_contents] =>
            log.info("Got Futures Contents snapshot, size = " + snapshot.data.size)
            snapshot.data.take(10) foreach {
              rec =>
                log.info("ContentsRecord; Future isin = " + rec.get_isin() + ", isin id = " + rec.get_isin_id() + ", name = " + rec.get_name() + ", session = " + rec.get_sess_id() + ", state = " + InstrumentState(rec.get_state()))
            }
        }
      }))

      sysEventsRepository ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot: Snapshot[FutInfo.sys_events] =>
            log.info("Got SysEvents Contents snapshot, size = " + snapshot.data.size)
            snapshot.data foreach {
              rec =>
                log.info("SysEvent; Event id = " + rec.get_event_id() + ", type = " + rec.get_event_type())
            }
        }
      }))

      // On connection Activated open listeners etc
      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) =>
            // Open Listener in Combined mode
            listener ! Listener.Open(ReplicationParams(ReplicationMode.Combined))

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