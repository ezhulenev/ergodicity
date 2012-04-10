package com.ergodicity.engine.plaza2.futures

import org.scalatest.Spec
import org.slf4j.LoggerFactory
import com.ergodicity.engine.plaza2.futures.SessionState._

class SessionSpec extends Spec {
  val log = LoggerFactory.getLogger(classOf[SessionSpec])

  describe("SessionState") {
    it("should support all codes") {
      assert(SessionState(0) == Assigned)
      assert(SessionState(1) == Online)
      assert(SessionState(2) == Suspended)
      assert(SessionState(3) == Canceled)
      assert(SessionState(4) == Completed)
    }
  }
}