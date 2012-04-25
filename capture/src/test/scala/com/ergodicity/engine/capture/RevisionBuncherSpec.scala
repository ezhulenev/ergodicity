package com.ergodicity.engine.capture

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.slf4j.LoggerFactory
import org.mockito.Mockito._
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import akka.actor.ActorSystem

class RevisionBuncherSpec extends TestKit(ActorSystem("RevisionBuncherSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[RevisionBuncherSpec])

  val Stream = "Stream"

  override def afterAll() {
    system.shutdown()
  }

  "RevisionBuncher" must {
    "be initialized in Idle state" in {
      val repository = mock(classOf[StreamRevisionTracker])
      val buncher = TestFSMRef(new RevisionBuncher(repository))
      assert(buncher.stateName == BuncherState.Idle)
    }

    "accumulate table revisions" in {
      val repository = mock(classOf[StreamRevisionTracker])
      val buncher = TestFSMRef(new RevisionBuncher(repository))

      buncher ! BunchRevision("table1", 101)
      assert(buncher.stateName == BuncherState.Accumulating)

      buncher ! BunchRevision("table1", 102)
      buncher ! BunchRevision("table2", 201)

      assert(buncher.stateData.get("table1") == 102)
      assert(buncher.stateData.get("table2") == 201)
    }

    "flush table revisions on request" in {
      val repository = mock(classOf[StreamRevisionTracker])
      val buncher = TestFSMRef(new RevisionBuncher(repository))

      buncher ! BunchRevision("table1", 101)
      buncher ! BunchRevision("table2", 201)

      buncher ! FlushBunch

      verify(repository).setRevision("table1", 101)
      verify(repository).setRevision("table2", 201)

      assert(buncher.stateName == BuncherState.Idle)
    }

    "recover after flush" in {
      val repository = mock(classOf[StreamRevisionTracker])
      val buncher = TestFSMRef(new RevisionBuncher(repository))

      buncher ! BunchRevision("table1", 101)
      buncher ! FlushBunch
      buncher ! BunchRevision("table1", 102)
      buncher ! FlushBunch
      verify(repository).setRevision("table1", 102)
    }
  }
}
