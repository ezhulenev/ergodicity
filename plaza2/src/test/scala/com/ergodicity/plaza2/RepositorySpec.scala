package com.ergodicity.plaza2

import akka.actor.ActorSystem
import RepositoryState._
import com.ergodicity.plaza2.scheme.FutInfo._
import org.mockito.Mockito._
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import com.ergodicity.plaza2.Repository.{SubscribeSnapshots, Snapshot}
import com.ergodicity.plaza2.DataStream._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging

class RepositorySpec  extends TestKit(ActorSystem()) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Repository" must {
    "be initialized in Idle state" in {
      val repository = TestFSMRef(Repository[SessionRecord], "Repository")
      log.info("State: " + repository.stateName)
      assert(repository.stateName == Idle)
    }

    "go to Synchronizing state as stream data begins" in {
      val repository = TestFSMRef(Repository[SessionRecord], "Repository")
      repository ! DataBegin
      assert(repository.stateName == Synchronizing)
    }

    "receive snapshot immediately in Consistent state" in {
      val repository = TestFSMRef(Repository[SessionRecord], "Repository")
      val rec = mock(classOf[SessionRecord])
      repository.setState(Consistent, Map(1l -> rec))

      repository ! SubscribeSnapshots(self)
      expectMsgType[Snapshot[SessionRecord]]
    }

    "handle new data" in {
      val repository = TestFSMRef(Repository[SessionRecord], "Repository")
      repository.setState(Consistent, Map())

      repository ! SubscribeSnapshots(self)      
      repository ! DataBegin
      assert(repository.stateName == Synchronizing)

      // Add two records
      repository ! DataInserted("table", mockRecord(1, 1, 0, 111))
      repository ! DataInserted("table", mockRecord(2, 2, 0, 112))
      assert(repository.stateData.size == 2)

      // Delete one of them
      repository ! DataDeleted("table", 2)
      assert(repository.stateData.size == 1)

      // Then insert another one
      repository ! DataInserted("table", mockRecord(3, 3, 0, 113))
      assert(repository.stateData.size == 2)

      // Close transaction
      repository ! DataEnd
      assert(repository.stateName == RepositoryState.Consistent)
      expectMsgType[Snapshot[SessionRecord]]

      // Remove old data
      repository ! DatumDeleted("table", 3)
      log.info("Data: "+repository.stateData)
      assert(repository.stateData.size == 1)
    }
  }

  private def mockRecord(replID: Long, replRev: Long, replAct: Long, sessionId: Int) = {
    SessionRecord(replID, replRev, replAct, sessionId, null, null, 0l, 0, null, null, 0l, 0l, null, null, 0l, null, null, null, null)
  }

}