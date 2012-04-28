package integration.ergodicity.core

import org.slf4j.LoggerFactory
import java.io.File
import org.scalatest.WordSpec
import plaza2.RequestType.CombinedDynamic
import plaza2.{TableSet, Connection => P2Connection, DataStream => P2DataStream}
import java.util.concurrent.TimeUnit
import akka.actor.{Actor, Props, ActorSystem}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import com.ergodicity.plaza2.DataStream.{JoinTable, SetLifeNumToIni, Open}
import com.ergodicity.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import com.ergodicity.plaza2.Connection.{ProcessMessages, Connect}
import com.ergodicity.plaza2._
import AkkaIntegrationConfigurations._
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import scheme.{Part, Deserializer}
import com.ergodicity.plaza2.{ConnectionState, Connection, DataStream, Repository}

class PartDataStreamIntegrationTest extends TestKit(ActorSystem("PartDataStreamIntegrationTest", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec {
  val log = LoggerFactory.getLogger(classOf[PartDataStreamIntegrationTest])

  val Host = "localhost"
  val Port = 4001
  val AppName = "PartDataStreamIntegrationTest"

  "DataStream" must {
    "do some stuff" in {
      val underlyingConnection = P2Connection()
      val connection = system.actorOf(Props(Connection(underlyingConnection)), "Connection")
      connection ! Connect(Host, Port, AppName)

      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, ConnectionState.Connected) => connection ! ProcessMessages(100);
        }
      })))

      val ini = new File("core/scheme/Part.ini")
      val tableSet = TableSet(ini)
      val underlyingStream = P2DataStream("FORTS_PART_REPL", CombinedDynamic, tableSet)

      val dataStream = TestFSMRef(new DataStream(underlyingStream), "FORTS_PART_REPL")
      dataStream ! SetLifeNumToIni(ini)

      // Handle options data
      val repository = TestFSMRef(new Repository[Part.PartRecord](), "PartRepo")

      dataStream ! JoinTable("part", repository, implicitly[Deserializer[Part.PartRecord]])
      dataStream ! Open(underlyingConnection)

      repository ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot@Snapshot(_, data: Iterable[Part.PartRecord]) =>
            log.info("Got snapshot; Size = "+snapshot.data.size)
            data.foreach(sessionContents =>
              log.info("Part: " + sessionContents)
            )
        }
      }))

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }

}