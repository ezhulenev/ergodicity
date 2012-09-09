package com.ergodicity.core.session

import org.joda.time.DateTime
import org.scala_tools.time.Implicits._
import SessionState._
import akka.event.Logging
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import com.ergodicity.core.AkkaConfigurations._
import com.ergodicity.core.session.SessionActor._
import akka.testkit.{TestActorRef, ImplicitSender, TestFSMRef, TestKit}
import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.Await
import akka.pattern.ask
import java.util.concurrent.TimeUnit
import akka.util.{Duration, Timeout}
import com.ergodicity.core.Mocking._
import com.ergodicity.core.Isin
import akka.actor.FSM.Transition
import akka.actor.FSM.CurrentState
import com.ergodicity.core.session.SessionActor.GetInstrumentActor
import akka.actor.FSM.SubscribeTransitionCallBack

class SessionActorSpec extends TestKit(ActorSystem("SessionActorSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  implicit val timeout = Timeout(1, TimeUnit.SECONDS)
  val primaryInterval = new DateTime to new DateTime
  val positionTransferInterval = new DateTime to new DateTime

  override def afterAll() {
    system.shutdown()
  }

  "SessionState" must {
    "support all codes" in {
      assert(SessionState(0) == Assigned)
      assert(SessionState(1) == Online)
      assert(SessionState(2) == Suspended)
      assert(SessionState(3) == Canceled)
      assert(SessionState(4) == Completed)
    }
  }

  "IntradayClearing" must {
    "handle state updates" in {
      val clearing = TestFSMRef(new IntradayClearing, "IntradayClearing")
      assert(clearing.stateName == IntradayClearingState.Undefined)

      clearing ! IntradayClearingState.Completed
      log.info("State: " + clearing.stateName)
      assert(clearing.stateName == IntradayClearingState.Completed)
    }
  }

  "Session" must {
    "be inititliazed in given state and terminate child clearing actor" in {
      val content = Session(100, 101, primaryInterval, None, None, positionTransferInterval)
      val session = TestFSMRef(SessionActor(content), "Session")

      val intClearing = session.underlyingActor.asInstanceOf[SessionActor].intradayClearing
      intClearing ! SubscribeTransitionCallBack(self)

      expectMsg(CurrentState(intClearing, IntradayClearingState.Undefined))
    }

    "apply state and int. clearing updates" in {
      val content = Session(100, 101, primaryInterval, None, None, positionTransferInterval)
      val session = TestFSMRef(SessionActor(content), "Session")

      // Subscribe for clearing transitions
      val intClearing = session.underlyingActor.asInstanceOf[SessionActor].intradayClearing
      intClearing ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(intClearing, IntradayClearingState.Undefined))

      session ! SessionState.Suspended
      assert(session.stateName == SessionState.Suspended)

      session ! IntradayClearingState.Running
      expectMsg(Transition(intClearing, IntradayClearingState.Undefined, IntradayClearingState.Running))
    }

    /*"handle FutSessContents" in {
      val content = Session(100, 101, primaryInterval, None, None, positionTransferInterval)
      val session = TestActorRef(new SessionActor(content), "Session2")

      val future = mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, 2)
      val repo = mockFuture(4023, 170971, "HYDR-16.04.12R3", "HYDRT0T3", "Репо инструмент на ОАО \"ГидроОГК\"", 4965, 2, 1)

      session ! FutInfoSessionContents(Snapshot(self, repo :: future :: Nil))

      Thread.sleep(300)

      val gmkFutures = system.actorFor("user/Session2/Futures/GMKR-6.12")

      gmkFutures ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(gmkFutures, InstrumentState.Suspended))

      val instrument = (session ? GetInstrumentActor(Isin("GMKR-6.12"))).mapTo[ActorRef]
      assert(Await.result(instrument, Duration(1, TimeUnit.SECONDS)) == gmkFutures)
    }

    "return assigned instruments" in {
      val session = Session(100, 101, primaryInterval, None, None, positionTransferInterval)
      val sessionActor = TestActorRef(new SessionActor(session, SessionState.Online, IntradayClearingState.Oncoming), "Session2")

      val future = mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, 2)
      val option = mockOption(4023, 111111, "RTS OPT", "OPT", "Some option contract", 4695)

      sessionActor ! FutInfoSessionContents(Snapshot(self, future :: Nil))
      sessionActor ! OptInfoSessionContents(Snapshot(self, option :: Nil))

      Thread.sleep(300)

      val assignedFuture = (sessionActor ? GetAssignedInstruments).mapTo[AssignedInstruments]
      val assigned = Await.result(assignedFuture, Duration(1, TimeUnit.SECONDS))

      log.info("Assigned instruments = "+assigned)
      assert(assigned.instruments.size == 2)
    }*/
  }
}