package com.ergodicity.engine.core

import org.slf4j.LoggerFactory
import org.scalatest.WordSpec
import org.joda.time.DateTime
import org.scala_tools.time.Implicits._
import akka.actor.{Terminated, ActorSystem}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import akka.actor.FSM.{CurrentState, Transition, SubscribeTransitionCallBack}
import SessionState._

class SessionSpec extends TestKit(ActorSystem()) with ImplicitSender with WordSpec {
  val log = LoggerFactory.getLogger(classOf[SessionSpec])

  val primaryInterval = new DateTime to new DateTime
  val positionTransferInterval = new DateTime to new DateTime


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

      expectMsg(Terminated(session))
      expectMsg(Terminated(intClearing))

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
  }
}