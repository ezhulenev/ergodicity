package com.ergodicity.core.session

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.Await
import akka.event.Logging
import akka.pattern.ask
import akka.testkit.TestActor.AutoPilot
import akka.testkit._
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging
import com.ergodicity.core.SessionsTracking.FutSessContents
import com.ergodicity.core.session.Instrument.Limits
import com.ergodicity.core.session.SessionActor.{GetInstrumentActor, GetState}
import com.ergodicity.core.{FutureContract, ShortIsin, IsinId, Isin}
import org.scalatest.{BeforeAndAfterAll, WordSpec}


class SessionContentsSpec extends TestKit(ActorSystem("SessionContentsSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val timeout = Timeout(1.second)

  val id = IsinId(166911)
  val isin = Isin("GMKR-6.12")
  val shortIsin = ShortIsin("GMM2")

  val instrument = Instrument(FutureContract(id, isin, shortIsin, "Future Contract"), Limits(100, 100))

  "SessionContentes with FuturesManager" must {
    import com.ergodicity.core.session._

    "return None if instrument not found" in {
      val contents = TestActorRef(new SessionContents[FutSessContents](onlineSession) with FuturesContentsManager, "Futures")
      contents ! FutSessContents(100, instrument, InstrumentState.Assigned)

      val request = (contents ? GetInstrumentActor(Isin("BadCode"))).mapTo[Option[ActorRef]]
      val result = Await.result(request, 1.second)
      assert(result == None)
    }

    "return instument reference if found" in {
      val contents = TestActorRef(new SessionContents[FutSessContents](onlineSession) with FuturesContentsManager, "Futures")
      contents ! FutSessContents(100, instrument, InstrumentState.Assigned)

      val request = (contents ? GetInstrumentActor(isin)).mapTo[Option[ActorRef]]
      val result = Await.result(request, 1.second)
      assert(result match {
        case Some(ref) => log.info("Ref = " + ref); true
        case _ => false
      })
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