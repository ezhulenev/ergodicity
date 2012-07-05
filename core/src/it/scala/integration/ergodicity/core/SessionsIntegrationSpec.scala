package integration.ergodicity.core

import org.slf4j.LoggerFactory
import java.io.File
import org.scalatest.WordSpec
import plaza2.RequestType.CombinedDynamic
import plaza2.{TableSet, Connection => P2Connection, DataStream => P2DataStream}
import akka.testkit.{TestActorRef, TestFSMRef, TestKit}
import java.util.concurrent.TimeUnit
import akka.actor.{Actor, Props, ActorSystem}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import com.ergodicity.plaza2.Connection.{ProcessMessages, Connect}
import integration.ergodicity.core.AkkaIntegrationConfigurations._
import com.ergodicity.core.Sessions
import com.ergodicity.plaza2.DataStream.{Open, SetLifeNumToIni}
import com.ergodicity.plaza2.{ConnectionState, Connection, DataStream}


class SessionsIntegrationSpec extends TestKit(ActorSystem("SessionsIntegrationSpec", ConfigWithDetailedLogging)) with WordSpec {
  val log = LoggerFactory.getLogger(classOf[SessionsIntegrationSpec])

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

      val futInfoIni = new File("core/scheme/FutInfo.ini")
      val futInfoTableSet = TableSet(futInfoIni)
      val FutInfoRepl = P2DataStream("FORTS_FUTINFO_REPL", CombinedDynamic, futInfoTableSet)
      val futInfoDataStream = TestFSMRef(new DataStream(FutInfoRepl), "FORTS_FUTINFO_REPL")

      val optInfoIni = new File("core/scheme/OptInfo.ini")
      val optInfoTableSet = TableSet(optInfoIni)
      val OptInfoRepl = P2DataStream("FORTS_OPTINFO_REPL", CombinedDynamic, optInfoTableSet)
      val optInfoDataStream = TestFSMRef(new DataStream(OptInfoRepl), "FORTS_OPTINFO_REPL")

      val sessions = TestActorRef(new Sessions(futInfoDataStream, optInfoDataStream), "Sessions")
      sessions ! Sessions.BindSessions

      Thread.sleep(1000)
      
      futInfoDataStream ! SetLifeNumToIni(futInfoIni)
      futInfoDataStream ! Open(underlyingConnection)

      optInfoDataStream ! SetLifeNumToIni(optInfoIni)
      optInfoDataStream ! Open(underlyingConnection)

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}