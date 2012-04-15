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
import com.ergodicity.engine.plaza2.Connection.{ProcessMessages, Connect}
import integration.ergodicity.engine.core.AkkaIntegrationConfigurations._
import com.ergodicity.engine.plaza2._
import com.ergodicity.engine.core.Sessions
import com.ergodicity.engine.plaza2.DataStream.Open._
import com.ergodicity.engine.plaza2.DataStream.{Open, SetLifeNumToIni}


class SessionsIntegrationSpec extends TestKit(ActorSystem("SessionsIntegrationSpec", ConfigWithDetailedLogging)) with WordSpec {
  val log = LoggerFactory.getLogger(classOf[ConnectionSpec])

  val Host = "localhost"
  val Port = 4001
  val AppName = "SessionsIntegrationSpec"

  "Sessions" must {
    "should work" in {
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
      val sessions = TestActorRef(new Sessions(dataStream), "Sessions")

      Thread.sleep(1000)
      
      dataStream ! SetLifeNumToIni(ini)
      dataStream ! Open(underlyingConnection)



      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}