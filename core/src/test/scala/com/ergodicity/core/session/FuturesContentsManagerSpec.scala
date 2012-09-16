package com.ergodicity.core.session

import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.testkit.TestActor.AutoPilot
import akka.testkit._
import com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging
import com.ergodicity.core.SessionsTracking.FutSessContents
import com.ergodicity.core.session.InstrumentParameters.{FutureParameters, Limits}
import com.ergodicity.core.session.SessionActor.GetState
import com.ergodicity.core.{ShortIsin, IsinId, Isin, FutureContract}
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}

class FuturesContentsManagerSpec extends TestKit(ActorSystem("FuturesContentsManagerSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val id = IsinId(166911)
  val isin = Isin("GMKR-6.12")
  val shortIsin = ShortIsin("GMM2")

  val instrument = FutureContract(id, isin, shortIsin, "Future Contract")
  val parameters = FutureParameters(100, Limits(100, 100))

  "FuturesContentsManager" must {

    "should track record updates merging with session state" in {

      val session: ActorRef = onlineSession
      val contents = TestActorRef(new SessionContents[FutSessContents](session) with FuturesContentsManager, "Futures")
      when("receive new instrument in suspended state")
      contents ! FutSessContents(100, instrument, parameters, InstrumentState.Suspended)

      then("should create actor for it")
      val instrumentActor = system.actorFor("user/Futures/GMKR-6.12")
      instrumentActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(instrumentActor, InstrumentState.Suspended))

      when("Instrument goes online")
      contents ! FutSessContents(100, instrument, parameters, InstrumentState.Online)
      then("should receive transition event")
      expectMsg(Transition(instrumentActor, InstrumentState.Suspended, InstrumentState.Online))

      when("session suspended")
      contents ! Transition(session, SessionState.Online, SessionState.Suspended)
      then("instrument should be suspended too")
      expectMsg(Transition(instrumentActor, InstrumentState.Online, InstrumentState.Suspended))

      when("instrument goes to Assigned state")
      contents ! FutSessContents(100, instrument, parameters, InstrumentState.Assigned)
      and("session goes online")
      contents ! Transition(session, SessionState.Suspended, SessionState.Online)
      then("instrument goes to Assigned")
      expectMsg(Transition(instrumentActor, InstrumentState.Suspended, InstrumentState.Assigned))
    }

    "merge session and instrument states" in {
      val contents = TestActorRef(new SessionContents[FutSessContents](onlineSession) with FuturesContentsManager, "Futures")
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

  def onlineSession = {
    val session = TestProbe()
    session.setAutoPilot(new AutoPilot {
      def run(sender: ActorRef, msg: Any) = msg match {
        case SubscribeTransitionCallBack(ref) =>
          ref ! CurrentState(session.ref, SessionState.Online)
          None
      }
    })
    session.ref
  }


}
