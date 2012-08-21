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
import com.ergodicity.core.Isins
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

  "IntradayClearing" must {
    "handle state updates" in {
      val clearing = TestFSMRef(new IntradayClearing(IntradayClearingState.Finalizing), "IntradayClearing")
      assert(clearing.stateName == IntradayClearingState.Finalizing)

      clearing ! IntradayClearingState.Completed
      log.info("State: " + clearing.stateName)
      assert(clearing.stateName == IntradayClearingState.Completed)
    }
  }

  "Session" must {
    "be inititliazed in given state and terminate child clearing actor" in {
      val content = SessionContent(100, 101, primaryInterval, None, None, positionTransferInterval)
      val session = TestFSMRef(Session(content, SessionState.Online, IntradayClearingState.Oncoming), "Session")

      assert(session.stateName == SessionState.Online)
      val intClearing = session.underlyingActor.asInstanceOf[Session].intradayClearing

      intClearing ! SubscribeTransitionCallBack(self)

      expectMsg(CurrentState(intClearing, IntradayClearingState.Oncoming))
    }

    "apply state and int. clearing updates" in {
      val content = SessionContent(100, 101, primaryInterval, None, None, positionTransferInterval)
      val session = TestFSMRef(Session(content, SessionState.Online, IntradayClearingState.Oncoming), "Session")

      // Subscribe for clearing transitions
      val intClearing = session.underlyingActor.asInstanceOf[Session].intradayClearing
      intClearing ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(intClearing, IntradayClearingState.Oncoming))

      session ! SessionState.Suspended
      assert(session.stateName == SessionState.Suspended)

      session ! IntradayClearingState.Running
      expectMsg(Transition(intClearing, IntradayClearingState.Oncoming, IntradayClearingState.Running))
    }

    "handle SessContentsRecord from FutInfo and return instument on request" in {
      val content = SessionContent(100, 101, primaryInterval, None, None, positionTransferInterval)
      val session = TestActorRef(new Session(content, SessionState.Online, IntradayClearingState.Oncoming), "Session2")

      val future = mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, 2)
      val repo = mockFuture(4023, 170971, "HYDR-16.04.12R3", "HYDRT0T3", "Репо инструмент на ОАО \"ГидроОГК\"", 4965, 2, 1)

      session ! FutInfoSessionContents(Snapshot(self, repo :: future :: Nil))

      Thread.sleep(300)

      val gmkFutures = system.actorFor("user/Session2/Futures/GMKR-6.12")

      gmkFutures ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(gmkFutures, InstrumentState.Suspended))

      val instrument = (session ? GetSessionInstrument(Isins(166911, "GMKR-6.12", "GMM2"))).mapTo[Option[ActorRef]]
      assert(Await.result(instrument, Duration(1, TimeUnit.SECONDS)) == Some(gmkFutures))
    }
  }
}