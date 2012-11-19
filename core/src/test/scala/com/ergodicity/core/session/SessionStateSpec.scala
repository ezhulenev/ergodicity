package com.ergodicity.core.session

import org.scalatest.WordSpec

class SessionStateSpec extends WordSpec {
  "Session State" must {
    "convert from int and back" in {
      assert(SessionState(SessionState.Assigned.toInt) == SessionState.Assigned)
      assert(SessionState(SessionState.Canceled.toInt) == SessionState.Canceled)
      assert(SessionState(SessionState.Completed.toInt) == SessionState.Completed)
      assert(SessionState(SessionState.Online.toInt) == SessionState.Online)
      assert(SessionState(SessionState.Suspended.toInt) == SessionState.Suspended)
    }
  }

  "Intermediate Clearing State" must {
    "convert from int and back" in {
      assert(IntradayClearingState(IntradayClearingState.Completed.toInt) == IntradayClearingState.Completed)
      assert(IntradayClearingState(IntradayClearingState.Finalizing.toInt) == IntradayClearingState.Finalizing)
      assert(IntradayClearingState(IntradayClearingState.Oncoming.toInt) == IntradayClearingState.Oncoming)
      assert(IntradayClearingState(IntradayClearingState.Running.toInt) == IntradayClearingState.Running)
      assert(IntradayClearingState(IntradayClearingState.Canceled.toInt) == IntradayClearingState.Canceled)
      assert(IntradayClearingState(IntradayClearingState.Undefined.toInt) == IntradayClearingState.Undefined)
    }
  }
}