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
import scheme.OptInfo
import com.ergodicity.cgate.config.Replication._
import akka.testkit.{TestActorRef, TestFSMRef, ImplicitSender, TestKit}
import akka.event.Logging
import java.util.concurrent.TimeUnit
import ru.micexrts.cgate.{P2TypeParser, CGate, Connection => CGConnection, Listener => CGListener}
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.StreamEvent.StreamData


class OptInfoIntegrationSpec extends TestKit(ActorSystem("OptInfoIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
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

  "OptInfo DataStream" must {
    "load events" in {
      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = TestFSMRef(new Connection(underlyingConnection), "Connection")

      val OptInfoDataStream = TestFSMRef(new DataStream, "OptInfoDataStream")

      // Listener
      val listenerConfig = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/OptInfo.ini"), "CustReplScheme")
      val underlyingListener = new CGListener(underlyingConnection, listenerConfig(), new DataStreamSubscriber(OptInfoDataStream))
      val listener = TestFSMRef(new Listener(underlyingListener), "Listener")

      OptInfoDataStream ! SubscribeStreamEvents(TestActorRef(new StreamDataThrottler(10) {
        override def handleData(data: StreamData) {
          import com.ergodicity.cgate.Protocol._
          data match {
            case StreamData(OptInfo.opt_sess_contents.TABLE_INDEX, bytes) =>
              val rec = implicitly[Reads[OptInfo.opt_sess_contents]] apply bytes
              log.info("ContentsRecord; Option isin = " + rec.get_isin() +
                ", isin id = " + rec.get_isin_id() +
                ", name = " + rec.get_name() +
                ", session = " + rec.get_sess_id())

            case StreamData(OptInfo.sys_events.TABLE_INDEX, bytes) =>
              val rec = implicitly[Reads[OptInfo.sys_events]] apply bytes
              log.info("SysEvent; Event id = " + rec.get_event_id() +
                ", type = " + rec.get_event_type() +
                ", message = " + rec.get_message() +
                ", session = " + rec.get_sess_id())
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