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
import com.ergodicity.core.position.Positions.BindPositions
import com.ergodicity.core.position.Positions

class PositionsIntegrationSpec extends TestKit(ActorSystem("PositionsIntegrationSpec", ConfigWithDetailedLogging)) with WordSpec {
  val log = LoggerFactory.getLogger(classOf[SessionsIntegrationSpec])

  val Host = "localhost"
  val Port = 4001
  val AppName = "PositionsIntegrationSpec"

  "Positions" must {
    "should work" in {
      val underlyingConnection = P2Connection()
      val connection = system.actorOf(Props(Connection(underlyingConnection)), "Connection")
      connection ! Connect(Host, Port, AppName)

      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, ConnectionState.Connected) => connection ! ProcessMessages(100);
        }
      })))

      val ini = new File("core/scheme/Pos.ini")
      val tableSet = TableSet(ini)
      val underlyingStream = P2DataStream("FORTS_POS_REPL", CombinedDynamic, tableSet)
      val dataStream = TestFSMRef(new DataStream(underlyingStream), "FORTS_POS_REPL")

      val positions = TestFSMRef(new Positions(dataStream), "Positions")
      positions ! BindPositions

      Thread.sleep(1000)

      dataStream ! SetLifeNumToIni(ini)
      dataStream ! Open(underlyingConnection)

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}