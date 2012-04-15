package integration.ergodicity.engine.core

import org.slf4j.LoggerFactory
import java.io.File
import org.scalatest.WordSpec
import plaza2.RequestType.CombinedDynamic
import plaza2.{TableSet, Connection => P2Connection, DataStream => P2DataStream}
import akka.testkit.{TestActorRef, TestFSMRef, TestKit}
import java.util.concurrent.TimeUnit
import akka.actor.{Actor, Props, ActorSystem}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import com.ergodicity.engine.plaza2.DataStream.{JoinTable, SetLifeNumToIni, Open}
import com.ergodicity.engine.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import com.ergodicity.engine.plaza2.Connection.{ProcessMessages, Connect}
import com.ergodicity.engine.plaza2.scheme.FutInfo
import com.ergodicity.engine.plaza2._
import AkkaIntegrationConfigurations._
import com.ergodicity.engine.core.model.Future
import scheme.FutInfo.{SessionRecord, Signs, SessContentsRecord}
import akka.testkit.TestActorRef._
import com.ergodicity.engine.core.{Sessions, SessionContents}

class FuturesDataStreamIntegrationSpec extends TestKit(ActorSystem("FuturesDataStreamIntegrationSpec", ConfigWithDetailedLogging)) with WordSpec {
  val log = LoggerFactory.getLogger(classOf[ConnectionSpec])


  val Host = "localhost"
  val Port = 4001
  val AppName = "FuturesDataStreamIntegrationSpec"

  val SessionContentToFuture = (record: SessContentsRecord) => new Future(record.isin, record.shortIsin, record.isinId, record.name)

  def IsFuture(record: SessContentsRecord) = {
    val signs = Signs(record.signs)
    !signs.spot && !signs.moneyMarket && signs.anonymous
  }

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

      val ini = new File("core/scheme/FutInfo.ini")
      val tableSet = TableSet(ini)
      val underlyingStream = P2DataStream("FORTS_FUTINFO_REPL", CombinedDynamic, tableSet)

      val dataStream = TestFSMRef(new DataStream(underlyingStream), "FuturesInfo")
      dataStream ! SetLifeNumToIni(ini)

      val repository = TestFSMRef(new Repository[SessContentsRecord]()(FutInfo.SessContentsDeserializer), "SessionsRepository")

      dataStream ! JoinTable(repository, "fut_sess_contents")
      dataStream ! Open(underlyingConnection)

      val futures = TestActorRef(new SessionContents(SessionContentToFuture), "Futures")

      repository ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot: Snapshot[SessContentsRecord] =>
            log.info("Got snapshot!!!")
            snapshot.data foreach {
              rec =>
                log.info("Record: " + rec)
            }
            futures ! snapshot.filter {
              IsFuture _
            }
        }
      }))

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}