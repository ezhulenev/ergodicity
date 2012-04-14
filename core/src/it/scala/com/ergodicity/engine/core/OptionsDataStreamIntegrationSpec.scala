package com.ergodicity.engine.core

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
import com.ergodicity.engine.plaza2.scheme.OptInfo.SessContentsRecord
import com.ergodicity.engine.plaza2.scheme.OptInfo
import com.ergodicity.engine.plaza2._

class OptionsDataStreamIntegrationSpec extends TestKit(ActorSystem("FuturesDataStreamIntegrationSpec", Config)) with WordSpec {
  val log = LoggerFactory.getLogger(classOf[ConnectionSpec])


  val Host = "localhost"
  val Port = 4001
  val AppName = "OptionsDataStreamIntegrationSpec"

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

      val ini = new File("plaza2/scheme/OptInfo.ini")
      val tableSet = TableSet(ini)
      val underlyingStream = P2DataStream("FORTS_OPTINFO_REPL", CombinedDynamic, tableSet)

      val dataStream = TestFSMRef(new DataStream(underlyingStream), "OptionsInfo")
      dataStream ! SetLifeNumToIni(ini)

      // Handle options data
      val repository = TestFSMRef(new Repository[SessContentsRecord]()(OptInfo.SessContentsDeserializer), "SessContentRepo")

      dataStream ! JoinTable(repository, "opt_sess_contents")
      dataStream ! Open(underlyingConnection)

      repository ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot@Snapshot(data: Iterable[SessContentsRecord]) =>
            log.info("Got snapshot")
            data.foreach(sessionContents =>
              log.info("SessContents: " + sessionContents)
            )
        }
      }))

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}