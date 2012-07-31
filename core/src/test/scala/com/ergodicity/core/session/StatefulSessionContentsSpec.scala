package com.ergodicity.core.session

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging
import com.ergodicity.core.common.FutureContract
import akka.actor.ActorSystem
import com.ergodicity.core.Mocking._
import com.ergodicity.cgate.scheme.FutInfo
import com.ergodicity.cgate.repository.Repository.Snapshot
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}

class StatefulSessionContentsSpec extends TestKit(ActorSystem("StatefulSessionContentsSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val gmkFuture = (state: Int) => mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, state)

  val SUSPENDED = 2
  val ONLINE = 1
  val ASSIGNED = 0

  "StatefulSessionContents" must {

    "should track record updates merging with session state" in {

      val contents = TestActorRef(new StatefulSessionContents[FutureContract, FutInfo.fut_sess_contents], "Futures")

      contents ! CurrentState(self, SessionState.Online)

      contents ! Snapshot(self, gmkFuture(SUSPENDED) :: Nil)

      val instrument = system.actorFor("user/Futures/GMKR-6.12")
      instrument ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(instrument, InstrumentState.Suspended))

      // Instrument goes Online
      contents ! Snapshot(self, gmkFuture(ONLINE) :: Nil)
      expectMsg(Transition(instrument, InstrumentState.Suspended, InstrumentState.Online))

      // Session suspended
      contents ! Transition(self, SessionState.Online, SessionState.Suspended)
      expectMsg(Transition(instrument, InstrumentState.Online, InstrumentState.Suspended))

      // Instrument goes Assigned and session Online
      contents ! Snapshot(self, gmkFuture(ASSIGNED) :: Nil)
      contents ! Transition(self, SessionState.Suspended, SessionState.Online)
      expectMsg(Transition(instrument, InstrumentState.Suspended, InstrumentState.Assigned))
    }

    "merge session and instrument states" in {
      val contents = TestActorRef(new StatefulSessionContents[FutureContract, FutInfo.fut_sess_contents], "Futures")
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
