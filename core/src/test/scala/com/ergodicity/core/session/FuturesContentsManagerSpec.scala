package com.ergodicity.core.session

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging
import akka.actor.{ActorRef, ActorSystem}
import com.ergodicity.core.Mocking._
import com.ergodicity.cgate.scheme.FutInfo
import akka.testkit._
import akka.actor.FSM.Transition
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.testkit.TestActor.AutoPilot
import com.ergodicity.core.session.SessionActor.GetState
import Implicits._

class FuturesContentsManagerSpec extends TestKit(ActorSystem("FuturesContentsManagerSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val gmkFuture = (state: Int) => mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, state)

  val SUSPENDED = 2
  val ONLINE = 1
  val ASSIGNED = 0

/*
  "StatefulSessionContents" must {

    "should track record updates merging with session state" in {

      val session: ActorRef = onlineSession
      val contents = TestActorRef(new SessionContents[FutInfo.fut_sess_contents](session) with FuturesContentsManager, "Futures")
      contents ! Snapshot(self, gmkFuture(SUSPENDED) :: Nil)

      val instrument = system.actorFor("user/Futures/GMKR-6.12")
      instrument ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(instrument, InstrumentState.Suspended))

      // InstrumentActor goes Online
      contents ! Snapshot(self, gmkFuture(ONLINE) :: Nil)
      expectMsg(Transition(instrument, InstrumentState.Suspended, InstrumentState.Online))

      // Session suspended
      contents ! Transition(session, SessionState.Online, SessionState.Suspended)
      expectMsg(Transition(instrument, InstrumentState.Online, InstrumentState.Suspended))

      // InstrumentActor goes Assigned and session Online
      contents ! Snapshot(self, gmkFuture(ASSIGNED) :: Nil)
      contents ! Transition(session, SessionState.Suspended, SessionState.Online)
      expectMsg(Transition(instrument, InstrumentState.Suspended, InstrumentState.Assigned))
    }

    "merge session and instrument states" in {
      val contents = TestActorRef(new SessionContents[FutInfo.fut_sess_contents](onlineSession) with FuturesContentsManager, "Futures")
      val underlying = contents.underlyingActor

      assert(underlying.merge(SessionState.Canceled, InstrumentState.Online) == InstrumentState.Canceled)
      assert(underlying.merge(SessionState.Completed, InstrumentState.Online) == InstrumentState.Completed)
      assert(underlying.merge(SessionState.Suspended, InstrumentState.Canceled) == InstrumentState.Canceled)
      assert(underlying.merge(SessionState.Assigned, InstrumentState.Canceled) == InstrumentState.Canceled)
      assert(underlying.merge(SessionState.Assigned, InstrumentState.Online) == InstrumentState.Assigned)
      assert(underlying.merge(SessionState.Online, InstrumentState.Completed) == InstrumentState.Completed)
      assert(underlying.merge(SessionState.Online, InstrumentState.Assigned) == InstrumentState.Assigned)
    }
  }
*/

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
