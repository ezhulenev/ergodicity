package com.ergodicity.core

import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.core.Sessions._
import akka.event.Logging
import session.Session.{OptInfoSessionContents, FutInfoSessionContents}
import session.SessionState
import org.scalatest.{BeforeAndAfterAll, GivenWhenThen, WordSpec}
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}
import Mocking._
import com.ergodicity.cgate.repository.Repository.Snapshot
import java.nio.ByteBuffer
import com.ergodicity.cgate.{DataStream, DataStreamState}
import akka.actor.{Kill, Terminated, ActorSystem}

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

/*
  "Sessions" must {

    "initialized in Binded state" in {
      val sessions = TestFSMRef(new Sessions(TestFSMRef(new DataStream()), TestFSMRef(new DataStream())), "Sessions")
      assert(sessions.stateName == SessionsState.Binded)
    }

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
      
      then("should go to LoadingSessions state")
      assert(sessions.stateName == SessionsState.LoadingSessions)

      when("receive Sessions snapthot with Online session")
      sessions ! Snapshot(sessionRepository, Seq(sessionRecord(46, 396, 4021, SessionState.Online)))

      then("should create actor for it")
      val session1 = sessions.stateData.asInstanceOf[TrackingSessions].sessions(SessionId(4021, OptionSessionId))
      watch(session1)
      session1 ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(session1, SessionState.Online))

      assert(sessions.stateData.asInstanceOf[TrackingSessions].ongoing == Some(session1))
      assert(sessions.stateData.asInstanceOf[TrackingSessions].sessions.size == 1)

      and("go to LoadingFuturesContents state")
      log.info("EBAKA = "+sessions.stateName)
      assert(sessions.stateName == SessionsState.LoadingFuturesContents, "Sessions state = " + sessions.stateName)
      
      when("receive Futures sessions contentes")
      sessions ! Snapshot(underlying.FutSessContentsRepository, List[FutInfo.fut_sess_contents]())
      
      then("should go to LoadingOptionsContents state")
      assert(sessions.stateName == SessionsState.LoadingOptionsContents)

      when("receive Options sessions contentes")
      sessions ! Snapshot(underlying.OptSessContentsRepository, List[OptInfo.opt_sess_contents]())

      then("should go to Online state")
      assert(sessions.stateName == SessionsState.Online)


      when("session completed")
      sessions ! Snapshot(sessionRepository, Seq(sessionRecord(46, 397, 4021, SessionState.Completed)))

      then("session actor should transite from Online to Copmpleted state")
      expectMsg(Transition(session1, SessionState.Online, SessionState.Completed))
      assert(sessions.stateData.asInstanceOf[TrackingSessions].ongoing == None)

      when("revision changed for same record")
      sessions ! Snapshot(sessionRepository, Seq(sessionRecord(46, 398, 4021, SessionState.Completed)))

      then("should remain in the same state")
      assert(sessions.stateData.asInstanceOf[TrackingSessions].ongoing == None)

      when("subscribe for ongoing sessions")
      sessions ! SubscribeOngoingSessions(self)

      then("should be returned current ongoing session which is None")
      expectMsg(OngoingSession(None))

      when("assigned new session")
      sessions ! Snapshot(sessionRepository, sessionRecord(46, 398, 4021, SessionState.Completed) :: sessionRecord(47, 399, 4022, SessionState.Assigned) :: Nil)

      then("new actor should be created")
      val session2 = sessions.stateData.asInstanceOf[TrackingSessions].sessions(SessionId(4022, OptionSessionId))

      and("notified about ongoing session transition")
      expectMsg(OngoingSessionTransition(Some(session2)))

      and("new session should be in Assigned state")
      watch(session2)
      session2 ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(session2, SessionState.Assigned))

      assert(sessions.stateData.asInstanceOf[TrackingSessions].sessions.size == 2)

      when("second session goes online and first removed")
      sessions ! Snapshot(sessionRepository, Seq(sessionRecord(47, 400, 4022, SessionState.Online)))

      then("first actor should be termindated")
      expectMsg(Transition(session2, SessionState.Assigned, SessionState.Online))
      expectMsg(Terminated(session1))

      assert(sessions.stateData.asInstanceOf[TrackingSessions].ongoing == Some(session2))
      assert(sessions.stateData.asInstanceOf[TrackingSessions].sessions.size == 1)

      when("all sessions removed")
      sessions ! Snapshot(sessionRepository, Seq.empty[FutInfo.session])

      then("second session should also be terminated")
      expectMsg(OngoingSessionTransition(None))
      expectMsg(Terminated(session2))

      assert(sessions.stateData.asInstanceOf[TrackingSessions].ongoing == None)
      assert(sessions.stateData.asInstanceOf[TrackingSessions].sessions.size == 0)
    }

    "should forward FutInfo.SessContentsRecord snapshot to child sessions" in {
      val FutInfoDS = TestFSMRef(new DataStream())
      val OptInfoDS = TestFSMRef(new DataStream())
      val sessions = TestFSMRef(new Sessions(FutInfoDS, OptInfoDS), "Sessions")

      val underlying = underlyingSessions(sessions)

      sessions.setState(SessionsState.Online, TrackingSessions(Map(SessionId(100l, 0l) -> self), None))

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

      sessions.setState(SessionsState.Online, TrackingSessions(Map(SessionId(0, 100l) -> self), None))

      val option1 = mockOption(100, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)
      val option2 = mockOption(101, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

      sessions ! Snapshot(underlying.OptSessContentsRepository, option1 :: option2 :: Nil)
      expectMsg(OptInfoSessionContents(Snapshot(underlying.OptSessContentsRepository, option1 :: Nil)))
    }
  }
*/

  def sessionRecord(replID: Long, revId: Long, sessionId: Int, sessionState: SessionState) = {
    import SessionState._
    
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