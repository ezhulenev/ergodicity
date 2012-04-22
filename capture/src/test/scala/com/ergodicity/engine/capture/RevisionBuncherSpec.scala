package com.ergodicity.engine.capture

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.slf4j.LoggerFactory
import org.mockito.Mockito._
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import akka.actor.{FSM, ActorSystem}

class RevisionBuncherSpec extends TestKit(ActorSystem("RevisionBuncherSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[MarketCaptureSpec])

  val Stream = "Stream"

  override def afterAll() {
    system.shutdown()
  }

  "RevisionBuncher" must {
    "be initialized in Idle state" in {
      val repository = mock(classOf[RevisionTracker])
      val buncher = TestFSMRef(new RevisionBuncher(repository, Stream))
      assert(buncher.stateName == RevisionBuncherState.Idle)
    }

    "accumulate table revisions" in {
      val repository = mock(classOf[RevisionTracker])
      val buncher = TestFSMRef(new RevisionBuncher(repository, Stream))

      buncher ! PushRevision("table1", 101)
      assert(buncher.stateName == RevisionBuncherState.Accumulating)

      buncher ! PushRevision("table1", 102)
      buncher ! PushRevision("table2", 201)

      assert(buncher.stateData.get("table1") == 102)
      assert(buncher.stateData.get("table2") == 201)
    }

    "flush table revision on timeout" in {
      val repository = mock(classOf[RevisionTracker])
      val buncher = TestFSMRef(new RevisionBuncher(repository, Stream))

      buncher ! PushRevision("table1", 101)
      buncher ! PushRevision("table2", 201)

      buncher ! FSM.StateTimeout

      verify(repository).setRevision(Stream, "table1", 101)
      verify(repository).setRevision(Stream, "table2", 201)

      assert(buncher.stateName == RevisionBuncherState.Idle)
    }

    "recover after flush" in {
      val repository = mock(classOf[RevisionTracker])
      val buncher = TestFSMRef(new RevisionBuncher(repository, Stream))

      buncher ! PushRevision("table1", 101)
      buncher ! FSM.StateTimeout
      buncher ! PushRevision("table1", 102)
      buncher ! FSM.StateTimeout
      verify(repository).setRevision(Stream, "table1", 102)
    }
  }
}
