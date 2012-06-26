package integration.ergodicity.core

import org.slf4j.LoggerFactory
import java.io.File
import org.scalatest.WordSpec
import plaza2.RequestType.CombinedDynamic
import plaza2.{TableSet, Connection => P2Connection, DataStream => P2DataStream}
import java.util.concurrent.TimeUnit
import akka.actor.{Actor, Props, ActorSystem}
import com.ergodicity.plaza2.DataStream.{BindTable, SetLifeNumToIni, Open}
import com.ergodicity.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import com.ergodicity.plaza2.Connection.{ProcessMessages, Connect}
import com.ergodicity.plaza2._
import AkkaIntegrationConfigurations._
import scheme.FutInfo.{Signs, SessContentsRecord}
import scheme.{Deserializer, FutInfo}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import com.ergodicity.core.common.FutureContract
import com.ergodicity.core.session.{TrackSessionState, SessionState, StatefulSessionContents}

class FutInfoDataStreamIntegrationSpec extends TestKit(ActorSystem("FutInfoDataStreamIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec {
  val log = LoggerFactory.getLogger(classOf[FutInfoDataStreamIntegrationSpec])


  val Host = "localhost"
  val Port = 4001
  val AppName = "FutInfoDataStreamIntegrationSpec"

  implicit val SessionContentToFuture = (record: SessContentsRecord) => new FutureContract(com.ergodicity.core.session.record2isin(record), record.name)

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

      val repository = TestFSMRef(new Repository[SessContentsRecord], "SessionsRepository")

      dataStream ! BindTable("fut_sess_contents", repository, implicitly[Deserializer[FutInfo.SessContentsRecord]])
      dataStream ! Open(underlyingConnection)

      val futures = TestActorRef(new StatefulSessionContents[FutureContract, FutInfo.SessContentsRecord](SessionState.Online), "Futures")
      futures ! TrackSessionState(self)


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