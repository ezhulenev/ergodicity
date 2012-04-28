package com.ergodicity.core.model

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.plaza2.Repository.Snapshot
import com.ergodicity.plaza2.scheme.FutInfo.SessContentsRecord
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.plaza2.scheme.FutInfo
import akka.actor.ActorSystem
import com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging

class StatefulSessionContentsSpec extends TestKit(ActorSystem("StatefulSessionContentsSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val gmkFuture = SessContentsRecord(7477, 47740, 0, 4023, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

  "StatefulSessionContents" must {

    "should track record updates merging with session state" in {
      val contents = TestActorRef(new StatefulSessionContents[FutureContract, FutInfo.SessContentsRecord](SessionState.Online), "Futures")
      contents ! JoinSession(self)
      expectMsgType[SubscribeTransitionCallBack]

      contents ! Snapshot(self, gmkFuture :: Nil)

      val instrument = system.actorFor("user/Futures/GMKR-6.12")
      instrument ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(instrument, InstrumentState.Suspended))

      // Instrument goes Online
      contents ! Snapshot(self, gmkFuture.copy(state = 1) :: Nil)
      expectMsg(Transition(instrument, InstrumentState.Suspended, InstrumentState.Online))

      // Session suspended
      contents ! Transition(self, SessionState.Online, SessionState.Suspended)
      expectMsg(Transition(instrument, InstrumentState.Online, InstrumentState.Suspended))

      // Instrument goes Assigned and session Online
      contents ! Snapshot(self, gmkFuture.copy(state = 0) :: Nil)
      contents ! Transition(self, SessionState.Suspended, SessionState.Online)
      expectMsg(Transition(instrument, InstrumentState.Suspended, InstrumentState.Assigned))
    }

    "merge session and instrument states" in {
      val contents = TestActorRef(new StatefulSessionContents[FutureContract, FutInfo.SessContentsRecord](SessionState.Online), "Futures")
      val underlying = contents.underlyingActor

      assert(underlying.mergeStates(SessionState.Canceled, InstrumentState.Online) == InstrumentState.Canceled)
      assert(underlying.mergeStates(SessionState.Completed, InstrumentState.Online) == InstrumentState.Completed)
      assert(underlying.mergeStates(SessionState.Suspended, InstrumentState.Canceled) == InstrumentState.Canceled)
      assert(underlying.mergeStates(SessionState.Assigned, InstrumentState.Canceled) == InstrumentState.Canceled)
      assert(underlying.mergeStates(SessionState.Assigned, InstrumentState.Online) == InstrumentState.Assigned)
      assert(underlying.mergeStates(SessionState.Online, InstrumentState.Completed) == InstrumentState.Completed)
      assert(underlying.mergeStates(SessionState.Online, InstrumentState.Assigned) == InstrumentState.Assigned)
    }
  }

}
