package com.ergodicity.core.session

import org.joda.time.DateTime
import org.scala_tools.time.Implicits._
import akka.actor.FSM.{CurrentState, Transition, SubscribeTransitionCallBack}
import SessionState._
import akka.event.Logging
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import com.ergodicity.core.AkkaConfigurations._
import com.ergodicity.core.session.Session.FutInfoSessionContents
import akka.testkit.{TestActorRef, ImplicitSender, TestFSMRef, TestKit}
import akka.actor.{ActorRef, Terminated, ActorSystem}
import akka.dispatch.Await
import akka.pattern.ask
import com.ergodicity.core.common.Isin
import java.util.concurrent.TimeUnit
import akka.util.{Duration, Timeout}
import com.ergodicity.core.Mocking._
import com.ergodicity.cgate.repository.Repository.Snapshot

class SessionSpec extends TestKit(ActorSystem("SessionSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
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

  "IntClearing" must {
    "handle state updates" in {
      val clearing = TestFSMRef(new IntClearing(IntClearingState.Finalizing), "IntClearing")
      assert(clearing.stateName == IntClearingState.Finalizing)

      clearing ! IntClearingState.Completed
      log.info("State: " + clearing.stateName)
      assert(clearing.stateName == IntClearingState.Completed)
    }
  }

  "Session" must {
    "be inititliazed in given state and terminate child clearing actor" in {
      val content = SessionContent(100, 101, primaryInterval, None, None, positionTransferInterval)
      val session = TestFSMRef(Session(content, SessionState.Online, IntClearingState.Oncoming))

      assert(session.stateName == SessionState.Online)
      val intClearing = session.stateData

      watch(session)
      watch(intClearing)

      session.stop()

      expectMsgType[Terminated]
      expectMsgType[Terminated]

      assert(session.isTerminated)
      assert(intClearing.isTerminated)
    }

    "apply state and int. clearing updates" in {
      val content = SessionContent(100, 101, primaryInterval, None, None, positionTransferInterval)
      val session = TestFSMRef(Session(content, SessionState.Online, IntClearingState.Oncoming), "Session")

      // Subscribe for clearing transitions
      val intClearing = session.stateData
      intClearing ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(intClearing, IntClearingState.Oncoming))

      session ! SessionState.Suspended
      assert(session.stateName == SessionState.Suspended)

      session ! IntClearingState.Running
      expectMsg(Transition(intClearing, IntClearingState.Oncoming, IntClearingState.Running))
    }

    "handle SessContentsRecord from FutInfo and return instument on request" in {
      val content = SessionContent(100, 101, primaryInterval, None, None, positionTransferInterval)
      val session = TestActorRef(new Session(content, SessionState.Online, IntClearingState.Oncoming), "Session2")

      val future = mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, 2)
      val repo = mockFuture(4023, 170971, "HYDR-16.04.12R3", "HYDRT0T3", "Репо инструмент на ОАО \"ГидроОГК\"", 4965, 2, 1)

      session ! FutInfoSessionContents(Snapshot(self, repo :: future :: Nil))

      Thread.sleep(300)

      val gmkFutures = system.actorFor("user/Session2/Futures/GMKR-6.12")

      gmkFutures ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(gmkFutures, InstrumentState.Suspended))

      val instrument = (session ? GetSessionInstrument(Isin(166911, "GMKR-6.12", "GMM2"))).mapTo[Option[ActorRef]]
      assert(Await.result(instrument, Duration(1, TimeUnit.SECONDS)) == Some(gmkFutures))
    }
  }
}