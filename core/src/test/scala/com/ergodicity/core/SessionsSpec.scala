package com.ergodicity.core

import com.ergodicity.plaza2.Repository.Snapshot
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.actor.{Terminated, ActorSystem}
import com.ergodicity.plaza2.scheme.FutInfo.SessionRecord
import com.ergodicity.core.Sessions._
import akka.event.Logging
import session.Session.{OptInfoSessionContents, FutInfoSessionContents}
import session.SessionState
import org.scalatest.{BeforeAndAfterAll, GivenWhenThen, WordSpec}
import com.ergodicity.plaza2.scheme.{OptInfo, FutInfo}
import akka.testkit.{TestFSMRef, TestActorRef, ImplicitSender, TestKit}
import plaza2.{DataStream => P2DataStream}
import org.mockito.Mockito._
import com.ergodicity.plaza2.{DataStreamState, DataStream}
import com.ergodicity.core.SessionsData.TrackingSessions

class SessionsSpec extends TestKit(ActorSystem("SessionsSpec")) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Sessions" must {

    "initialized in Idle state" in {
      val futuresDs = mock(classOf[P2DataStream])
      val optionsDs = mock(classOf[P2DataStream])

      val sessions = TestFSMRef(new Sessions(TestFSMRef(DataStream(futuresDs)), TestFSMRef(DataStream(optionsDs))), "Sessions")
      assert(sessions.stateName == SessionsState.Idle)
    }

    "track sessions state" in {
      val futuresDs = mock(classOf[P2DataStream])
      val optionsDs = mock(classOf[P2DataStream])

      val FutInfo = TestFSMRef(DataStream(futuresDs))
      val OptInfo = TestFSMRef(DataStream(optionsDs))
      val sessions = TestFSMRef(new Sessions(FutInfo, OptInfo), "Sessions")

      val underlying = sessions.underlyingActor.asInstanceOf[Sessions]
      val sessionRepository = underlying.SessionRepository
      
      when("BindSessions received")
      sessions ! BindSessions
      
      then("should go to BindingState")
      assert(sessions.stateName == SessionsState.Binding)
      
      when("both data Streams goes online")
      sessions ! Transition(FutInfo, DataStreamState.Opening, DataStreamState.Online)
      sessions ! Transition(OptInfo, DataStreamState.Opening, DataStreamState.Online)
      
      then("should go to LoadingSessions state")
      assert(sessions.stateName == SessionsState.LoadingSessions)

      when("receive Sessions snapthot with Online session")
      sessions ! Snapshot(sessionRepository, Seq(sessionRecord(46, 396, 4021, SessionState.Online)))

      then("should create actor for it")
      val session1 = sessions.stateData.asInstanceOf[TrackingSessions].sessions(SessionId(4021, 3547))
      watch(session1)
      session1 ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(session1, SessionState.Online))

      assert(sessions.stateData.asInstanceOf[TrackingSessions].ongoing == Some(session1))
      assert(sessions.stateData.asInstanceOf[TrackingSessions].sessions.size == 1)

      and("go to LoadingFuturesContens state")
      assert(sessions.stateName == SessionsState.LoadingFuturesContents)

      /*
      when("session completed")
      sessions ! Snapshot(sessionRepository, Seq(sessionRecord(46, 397, 4021, SessionState.Completed)))

      then("actor should transite from Online to Copmpleted state")
      expectMsg(Transition(session1, SessionState.Online, SessionState.Completed))
      assert(underlying.ongoingSession == None)

      when("revision changed for same record")
      sessions ! Snapshot(sessionRepository, Seq(sessionRecord(46, 398, 4021, SessionState.Completed)))

      then("should remain in the same state")
      assert(underlying.ongoingSession == None)

      when("assigned new session")
      sessions ! Snapshot(sessionRepository, sessionRecord(46, 398, 4021, SessionState.Completed) :: sessionRecord(47, 399, 4022, SessionState.Assigned) :: Nil)

      then("new actor should be created")
      val session2 = underlying.trackingSessions(SessionId(4022, 3547))
      watch(session2)

      session2 ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(session2, SessionState.Assigned))

      sessions ! GetOngoingSession
      expectMsg(OngoingSession(Some(session2)))

      assert(underlying.trackingSessions.size == 2)

      when("second session goes online and first removed")
      sessions ! Snapshot(sessionRepository, Seq(sessionRecord(47, 400, 4022, SessionState.Online)))

      then("first actor should be termindated")
      expectMsg(Transition(session2, SessionState.Assigned, SessionState.Online))
      expectMsg(Terminated(session1))

      assert(underlying.ongoingSession == Some(session2))
      assert(underlying.trackingSessions.size == 1)

      when("all sessions removed")
      sessions ! Snapshot(sessionRepository, Seq.empty[SessionRecord])

      then("second session should also be terminated")
      expectMsg(Terminated(session2))

      assert(underlying.ongoingSession == None)
      assert(underlying.trackingSessions.size == 0)*/
    }

    /*"should forward FutInfo.SessContentsRecord snapshot to child sessions" in {
      val sessions = TestActorRef(new Sessions, "SessionsSpec")

      val underlying = sessions.underlyingActor
      val futSessContentsRepository = underlying.FutSessContentsRepository

      underlying.trackingSessions = Map(SessionId(100l, 0l) -> self)

      val future1 = FutInfo.SessContentsRecord(7477, 47740, 0, 100, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)
      val future2 = FutInfo.SessContentsRecord(7477, 47740, 0, 102, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

      sessions ! Snapshot(futSessContentsRepository, future1 :: future2 :: Nil)
      expectMsg(FutInfoSessionContents(Snapshot(futSessContentsRepository, future1 :: Nil)))
    }

    "should forward OptInfo.SessContentsRecord snapshot to child sessions" in {
      val sessions = TestActorRef(new Sessions, "SessionsSpec")

      val underlying = sessions.underlyingActor
      val optSessContentsRepository = underlying.OptSessContentsRepository

      underlying.trackingSessions = Map(SessionId(0l, 100l) -> self)

      val option1 = OptInfo.SessContentsRecord(10881, 20023, 0, 100, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)
      val option2 = OptInfo.SessContentsRecord(10881, 20023, 0, 101, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

      sessions ! Snapshot(optSessContentsRepository, option1 :: option2 :: Nil)
      expectMsg(OptInfoSessionContents(Snapshot(optSessContentsRepository, option1 :: Nil)))
    }*/
  }

  def sessionRecord(replID: Long, revId: Long, sessionId: Int, sessionState: SessionState) = {
    import SessionState._

    val begin = "2012/04/12 07:15:00.000"
    val end = "2012/04/12 14:45:00.000"
    val interClBegin = "2012/04/12 12:00:00.000"
    val interClEnd = "2012/04/12 12:05:00.000"
    val eveBegin = "2012/04/11 15:30:00.000"
    val eveEnd = "2012/04/11 23:50:00.000"
    val monBegin = "2012/04/12 07:00:00.000"
    val monEnd = "2012/04/12 07:15:00.000"
    val posTransferBegin = "2012/04/12 13:00:00.000"
    val posTransferEnd = "2012/04/12 13:15:00.000"

    val stateValue = sessionState match {
      case Assigned => 0
      case Online => 1
      case Suspended => 2
      case Canceled => 3
      case Completed => 4
    }

    SessionRecord(replID, revId, 0, sessionId, begin, end, stateValue, 3547, interClBegin, interClEnd, 5136, 1, eveBegin, eveEnd, 1, monBegin, monEnd, posTransferBegin, posTransferEnd)
  }

}