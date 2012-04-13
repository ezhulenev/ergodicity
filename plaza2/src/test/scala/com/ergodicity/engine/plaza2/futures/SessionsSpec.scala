package com.ergodicity.engine.plaza2.futures

import org.slf4j.LoggerFactory
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.engine.plaza2.DataStream.JoinTable
import com.ergodicity.engine.plaza2.Repository.Snapshot
import com.ergodicity.engine.plaza2.scheme.SessionRecord
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.actor.{Terminated, ActorSystem}
import org.scalatest.{GivenWhenThen, WordSpec}
import com.ergodicity.engine.plaza2.futures.Sessions.{OngoingSession, GetOngoingSession}

class SessionsSpec extends TestKit(ActorSystem()) with ImplicitSender with WordSpec with GivenWhenThen {
  val log = LoggerFactory.getLogger(classOf[SessionSpec])

  "Sessions" must {
    "track sessions state" in {

      val sessions = TestActorRef(new Sessions(self), "SessionsSpec")
      expectMsgType[JoinTable]
      val underlying = sessions.underlyingActor

      when("initialized with Online session")
      sessions ! Snapshot(Seq(sessionRecord(46, 396, 4021, SessionState.Online)))

      then("should create actor for it")
      val session1 = underlying.trackingSessions(4021)
      watch(session1)

      session1 ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(session1, SessionState.Online))

      assert(underlying.ongoingSession == Some(session1))
      assert(underlying.trackingSessions.size == 1)

      when("session completed")
      sessions ! Snapshot(Seq(sessionRecord(46, 397, 4021, SessionState.Completed)))

      then("actor should transite from Online to Copmpleted state")
      expectMsg(Transition(session1, SessionState.Online, SessionState.Completed))
      assert(underlying.ongoingSession == None)

      when("revision changed for same record")
      sessions ! Snapshot(Seq(sessionRecord(46, 398, 4021, SessionState.Completed)))
      
      then("should remain in the same state")
      assert(underlying.ongoingSession == None)

      when("assigned new session")
      sessions ! Snapshot(sessionRecord(46, 398, 4021, SessionState.Completed) :: sessionRecord(47, 399, 4022, SessionState.Assigned) :: Nil)

      then("new actor should be created")
      val session2 = underlying.trackingSessions(4022)
      watch(session2)
      
      session2 ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(session2, SessionState.Assigned))

      sessions ! GetOngoingSession
      expectMsg(OngoingSession(Some(session2)))

      assert(underlying.trackingSessions.size == 2)

      when("second session goes online and first removed")
      sessions ! Snapshot(Seq(sessionRecord(47, 400, 4022, SessionState.Online)))

      then("first actor should be termindated")
      expectMsg(Transition(session2, SessionState.Assigned, SessionState.Online))
      expectMsg(Terminated(session1))

      assert(underlying.ongoingSession == Some(session2))
      assert(underlying.trackingSessions.size == 1)

      when("all sessions removed")
      sessions ! Snapshot(Seq())
      
      then("second session should also be terminated")
      expectMsg(Terminated(session2))

      assert(underlying.ongoingSession == None)
      assert(underlying.trackingSessions.size == 0)
    }
  }

  def sessionRecord(replID: Long, revId: Long, sessionId: Long, sessionState: SessionState) = {
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