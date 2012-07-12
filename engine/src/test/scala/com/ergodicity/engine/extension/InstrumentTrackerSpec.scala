package com.ergodicity.engine.extension

import com.ergodicity.engine._
import extensions.InstrumentTrackerData.TrackedSession
import extensions.{InstrumentTrackerData, InstrumentTrackerState, InstrumentTracker}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.ActorSystem
import akka.testkit._
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.core.SessionsState
import com.ergodicity.core.Sessions.{OngoingSessionTransition, CurrentOngoingSession, SubscribeOngoingSessions}
import java.security.Security
import com.ergodicity.core.common._


class InstrumentTrackerSpec extends TestKit(ActorSystem("InstrumentTrackerSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }
  
  "InsrumentTracker" must {
    "initialized in Binding state and subsribe for sessions" in {
      val sessions = TestProbe()
      val tracker = TestFSMRef(new InstrumentTracker(sessions.ref))
      sessions.expectMsg(SubscribeTransitionCallBack(tracker))
      assert(tracker.stateName == InstrumentTrackerState.BindingSessions)
    }

    "go ot waiting ongoing session on Sessions goes online" in {
      val sessions = TestProbe()
      val tracker = TestFSMRef(new InstrumentTracker(sessions.ref))
      sessions.expectMsg(SubscribeTransitionCallBack(tracker))

      tracker ! CurrentState(sessions.ref, SessionsState.Online)
      sessions.expectMsg(SubscribeOngoingSessions(tracker))
      assert(tracker.stateName == InstrumentTrackerState.WaitingOngoingSession)
    }

    "go to tracking when receive ongoing session" in {
      val sessions = TestProbe()
      val tracker = TestFSMRef(new InstrumentTracker(sessions.ref))
      tracker.setState(InstrumentTrackerState.WaitingOngoingSession)

      val session = TestProbe()
      tracker ! CurrentOngoingSession(Some(session.ref))

      assert(tracker.stateName == InstrumentTrackerState.TrackingSession)
      assert(tracker.stateData == TrackedSession(session.ref))
    }

    "go to Waiting when ongoing transition" in {
      val sessions = TestProbe()
      val session = TestProbe()
      val tracker = TestFSMRef(new InstrumentTracker(sessions.ref))
      tracker.setState(InstrumentTrackerState.TrackingSession, TrackedSession(session.ref))

      tracker ! OngoingSessionTransition(None)

      assert(tracker.stateName == InstrumentTrackerState.WaitingOngoingSession)
      assert(tracker.stateData == InstrumentTrackerData.Blank)
    }

    "switch ongoing session on transition" in {
      val sessions = TestProbe()
      val session1 = TestProbe()
      val session2 = TestProbe()
      val tracker = TestFSMRef(new InstrumentTracker(sessions.ref))
      tracker.setState(InstrumentTrackerState.TrackingSession, TrackedSession(session1.ref))

      tracker ! OngoingSessionTransition(Some(session2.ref))

      assert(tracker.stateName == InstrumentTrackerState.TrackingSession)
      assert(tracker.stateData == TrackedSession(session2.ref))
    }
  }
}