package com.ergodicity.core

import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.core.Sessions._
import akka.event.Logging
import akka.util.duration._
import session.Session.{OptInfoSessionContents, FutInfoSessionContents}
import session.SessionState
import org.scalatest.{BeforeAndAfterAll, GivenWhenThen, WordSpec}
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.cgate.scheme.FutInfo
import Mocking._
import com.ergodicity.cgate.repository.Repository.Snapshot
import java.nio.ByteBuffer
import com.ergodicity.cgate.{DataStream, DataStreamState}
import akka.actor.{Kill, Terminated, ActorSystem}
import com.ergodicity.cgate.StreamEvent.StreamData

class SessionsSpec extends TestKit(ActorSystem("SessionsSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll {
  val log = Logging(system, self)
  
  val OptionSessionId = 3547

  override def afterAll() {
    system.shutdown()
  }

  private def underlyingSessions(ref: TestFSMRef[SessionsState, StreamStates, Sessions]) = {
    val underlying = ref.underlyingActor.asInstanceOf[Sessions]

    // Kill all repositories to prevent Snapshot's from Empty state
    underlying.SessionRepository ! Kill
    underlying.FutSessContentsRepository ! Kill
    underlying.OptSessContentsRepository ! Kill

    // Let them die
    Thread.sleep(100)

    underlying
  }

  private def sessionDataReady(id: Int) = {
    val buff = ByteBuffer.allocate(100)
    val event = new FutInfo.sys_events(buff)
    event.set_event_type(1)
    event.set_sess_id(id)
    StreamData(FutInfo.sys_events.TABLE_INDEX, event.getData)
  }

  "Sessions" must {

    "track sessions state" in {
      val FutInfoDS = TestFSMRef(new DataStream(), "FutInfoDS")
      val OptInfoDS = TestFSMRef(new DataStream(), "OptInfoDS")

      val sessions = TestFSMRef(new Sessions(FutInfoDS, OptInfoDS), "Sessions")
      val underlying = underlyingSessions(sessions)

      val sessionRepository = underlying.SessionRepository

      then("should initialized in Binded state")
      assert(sessions.stateName == SessionsState.Binded)

      when("both data Streams goes online")
      sessions ! Transition(FutInfoDS, DataStreamState.Opened, DataStreamState.Online)
      sessions ! Transition(OptInfoDS, DataStreamState.Opened, DataStreamState.Online)
      
      then("should go to Online state")
      assert(sessions.stateName == SessionsState.Online)

      when("subscribe for ongoing sessions")
      sessions ! SubscribeOngoingSessions(self)

      then("should be returned None")
      expectMsg(OngoingSession(None))

      when("receive SessionDataReady")
      underlying.sysEventsDispatcher ! sessionDataReady(4021)
      Thread.sleep(100)

      then("should ask for Sessions snapshot")
      // can't check it as SessionRepository unavailable for mocking

      when("receive Sessions snapthot with Online session")
      sessions ! UpdateOngoingSessions(Snapshot(sessionRepository, Seq(sessionRecord(46, 396, 4021, SessionState.Online))))

      then("should create actor for it")
      val session1 = underlying.trackingSessions(SessionId(4021, OptionSessionId))
      assert(underlying.trackingSessions.size == 1)

      and("should be notified about ongoing session update")
      assert(underlying.ongoingSession == Some((SessionId(4021, OptionSessionId), session1)))
      expectMsg(OngoingSessionTransition(None, Some((SessionId(4021, OptionSessionId), session1))))

      and("it's state should be Online")
      watch(session1)
      session1 ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(session1, SessionState.Online))

      when("session completed")
      sessions ! Snapshot(sessionRepository, Seq(sessionRecord(46, 397, 4021, SessionState.Completed)))

      then("session actor should transite from Online to Copmpleted state")
      expectMsg(Transition(session1, SessionState.Online, SessionState.Completed))

      when("revision changed for same record")
      sessions ! Snapshot(sessionRepository, Seq(sessionRecord(46, 398, 4021, SessionState.Completed)))

      then("should remain in the same state")
      assert(underlying.ongoingSession == Some((SessionId(4021, OptionSessionId), session1)))

      when("new session data is ready")
      underlying.sysEventsDispatcher ! sessionDataReady(4022)
      Thread.sleep(100)

      then("should ask for Sessions snapshot")
      // can't check it as SessionRepository unavailable for mocking

      when("receive new sessions snapshot")
      sessions ! UpdateOngoingSessions(Snapshot(sessionRepository, sessionRecord(46, 398, 4021, SessionState.Completed) :: sessionRecord(47, 399, 4022, SessionState.Assigned) :: Nil))

      then("new actor should be created")
      val session2 = underlying.trackingSessions(SessionId(4022, OptionSessionId))

      and("notified about ongoing session transition")
      assert(underlying.ongoingSession == Some((SessionId(4022, OptionSessionId), session2)))
      expectMsg(OngoingSessionTransition(Some((SessionId(4021, OptionSessionId), session1)), Some((SessionId(4022, OptionSessionId), session2))))

      and("new session should be in Assigned state")
      watch(session2)
      session2 ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(session2, SessionState.Assigned))

      assert(underlying.trackingSessions.size == 2)

      when("one more session data is ready")
      underlying.sysEventsDispatcher ! sessionDataReady(4023)
      Thread.sleep(100)

      then("should ask for Sessions snapshot")
      // can't check it as SessionRepository unavailable for mocking

      when("first session removed from snapshot, and third one is Assigned")
      sessions ! UpdateOngoingSessions(Snapshot(sessionRepository, Seq(sessionRecord(47, 400, 4022, SessionState.Online), sessionRecord(48, 400, 4023, SessionState.Assigned))))
      sessions ! Snapshot(sessionRepository, Seq(sessionRecord(47, 400, 4022, SessionState.Online), sessionRecord(48, 400, 4023, SessionState.Assigned)))

      then("first actor should be termindated, and new one for third session created")
      expectMsgAllOf(Terminated(session1), Transition(session2, SessionState.Assigned, SessionState.Online))

      assert(underlying.ongoingSession == Some((SessionId(4022, OptionSessionId), session2)))
      assert(underlying.trackingSessions.size == 2)

      expectNoMsg(300.millis)
    }

    "should forward FutInfo.SessContentsRecord snapshot to child sessions" in {
      val FutInfoDS = TestFSMRef(new DataStream())
      val OptInfoDS = TestFSMRef(new DataStream())
      val sessions = TestFSMRef(new Sessions(FutInfoDS, OptInfoDS), "Sessions")

      val underlying = underlyingSessions(sessions)

      sessions.setState(SessionsState.Online)
      underlying.trackingSessions(SessionId(100l, 0l)) = self

      val future1 = mockFuture(100, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)
      val future2 = mockFuture(102, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

      sessions ! Snapshot(underlying.FutSessContentsRepository, future1 :: future2 :: Nil)
      expectMsg(FutInfoSessionContents(Snapshot(underlying.FutSessContentsRepository, future1 :: Nil)))
    }

    "should forward OptInfo.SessContentsRecord snapshot to child sessions" in {
      val FutInfoDS = TestFSMRef(new DataStream)
      val OptInfoDS = TestFSMRef(new DataStream)
      val sessions = TestFSMRef(new Sessions(FutInfoDS, OptInfoDS), "Sessions")

      val underlying = underlyingSessions(sessions)

      sessions.setState(SessionsState.Online)
      underlying.trackingSessions(SessionId(0, 100l)) = self

      val option1 = mockOption(100, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)
      val option2 = mockOption(101, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

      sessions ! Snapshot(underlying.OptSessContentsRepository, option1 :: option2 :: Nil)
      expectMsg(OptInfoSessionContents(Snapshot(underlying.OptSessContentsRepository, option1 :: Nil)))
    }
  }

  def sessionRecord(replID: Long, revId: Long, sessionId: Int, sessionState: SessionState) = {
    val buffer = ByteBuffer.allocate(1000)

    val stateValue = SessionState.decode(sessionState)
    
    val session = new FutInfo.session(buffer)
    session.set_replID(replID)
    session.set_replRev(revId)
    session.set_replAct(0)
    session.set_sess_id(sessionId)
    session.set_begin(0l)
    session.set_end(0l)
    session.set_opt_sess_id(OptionSessionId)
    session.set_state(stateValue)        
    session   
  }
}