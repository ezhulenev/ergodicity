package com.ergodicity.core.session

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.pattern.ask
import akka.util.duration._
import com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging
import com.ergodicity.core.Isins
import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.Await
import akka.util.Timeout
import com.ergodicity.cgate.scheme.FutInfo
import com.ergodicity.cgate.repository.Repository.Snapshot
import com.ergodicity.core.Mocking._
import akka.testkit._
import akka.testkit.TestActor.AutoPilot
import com.ergodicity.core.session.Session.GetState
import Implicits._


class SessionContentsSpec extends TestKit(ActorSystem("SessionContentsSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val timeout = Timeout(1.second)

  val gmkFuture = mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, 2)

  "SessionContentes with FuturesManager" must {
    import com.ergodicity.core.session._

    "return None if no instument found" in {
      val contents = TestActorRef(new SessionContents[FutInfo.fut_sess_contents](onlineSession) with FuturesContentsManager, "Futures")
      val future = (contents ? GetSessionInstrument(Isins(100, "BadCode", "BadShortCode"))).mapTo[Option[ActorRef]]
      assert(Await.result(future, 1.second) == None)
    }

    "return instument reference if found" in {
      val contents = TestActorRef(new SessionContents[FutInfo.fut_sess_contents](onlineSession) with FuturesContentsManager, "Futures")
      contents ! Snapshot(self, gmkFuture :: Nil)

      val gmk = system.actorFor("user/Futures/GMKR-6.12")
      val future = (contents ? GetSessionInstrument(Isins(166911, "GMKR-6.12", "GMM2"))).mapTo[Option[ActorRef]]
      assert(Await.result(future, 1.second) == Some(gmk))
    }
  }

  def onlineSession = {
    val session = TestProbe()
    session.setAutoPilot(new AutoPilot {
      def run(sender: ActorRef, msg: Any) = msg match {
        case GetState =>
          sender ! SessionState.Online
          None
      }
    })
    session.ref
  }
}