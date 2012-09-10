package com.ergodicity.capture

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import akka.actor.ActorSystem
import com.ergodicity.cgate.Protocol._
import java.nio.ByteBuffer
import akka.util.duration._
import com.ergodicity.cgate.StreamEvent._
import com.ergodicity.cgate.scheme.FutInfo
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.StreamEvent.LifeNumChanged
import com.ergodicity.capture.RepositoryState.Consistent
import com.ergodicity.capture.Repository.{GetSnapshot, Snapshot, SubscribeSnapshots}

class RepositorySpec extends TestKit(ActorSystem("RepositorySpec", AkkaConfigurations.ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  def Session = {
    val EmptyBuffer = ByteBuffer.allocate(100)
    new FutInfo.session(EmptyBuffer)
  }

  def Session(replId: Long, replRev: Long, replAct: Long, id: Int) = {
    val EmptyBuffer = ByteBuffer.allocate(100)
    val session = new FutInfo.session(EmptyBuffer)
    session.set_replID(replId)
    session.set_replRev(replRev)
    session.set_replAct(replAct)
    session.set_sess_id(id)
    session
  }

  "Repository" must {

    "be initialized in Consistent state" in {
      val repository = TestFSMRef(Repository[FutInfo.session](), "Repository")
      assert(repository.stateName == RepositoryState.Consistent)
    }

    "receive snapshot immediately in Consistent state" in {
      val repository = TestFSMRef(Repository[FutInfo.session](), "Repository")
      val underlying = repository.underlyingActor.asInstanceOf[Repository[FutInfo.session]]

      underlying.storage(1l) = Session
      repository.setState(Consistent)

      repository ! SubscribeSnapshots(self)
      val snapshot = receiveOne(1.second).asInstanceOf[Snapshot[FutInfo.session]]
      assert(snapshot.data.size == 1)
    }

    "clean on LifeNumChanged" in {
      val repository = TestFSMRef(Repository[FutInfo.session](), "Repository")
      val underlying = repository.underlyingActor.asInstanceOf[Repository[FutInfo.session]]

      underlying.storage(1l) = Session
      repository.setState(Consistent)

      repository ! SubscribeSnapshots(self)
      var snapshot = receiveOne(1.second).asInstanceOf[Snapshot[FutInfo.session]]
      assert(snapshot.data.size == 1)

      repository ! LifeNumChanged(1)
      snapshot = receiveOne(1.second).asInstanceOf[Snapshot[FutInfo.session]]
      assert(snapshot.data.size == 0)
    }

    "return snapshot immediatly in consistend state on GetSnapshot" in {
      val repository = TestFSMRef(Repository[FutInfo.session](), "Repository")
      val underlying = repository.underlyingActor.asInstanceOf[Repository[FutInfo.session]]
      underlying.storage(1l) = Session
      underlying.storage(2l) = Session
      repository ! GetSnapshot
      val snapshot = receiveOne(100.millis).asInstanceOf[Snapshot[FutInfo.session]]
      assert(snapshot.data.size == 2)
    }

    "return snapshot after returned to consistend state on GetSnapshot" in {
      val repository = TestFSMRef(Repository[FutInfo.session](), "Repository")
      val underlying = repository.underlyingActor.asInstanceOf[Repository[FutInfo.session]]

      repository ! TnBegin
      underlying.storage(1l) = Session
      underlying.storage(2l) = Session
      repository ! GetSnapshot
      expectNoMsg(300.millis)
      repository ! TnCommit

      val snapshot = receiveOne(100.millis).asInstanceOf[Snapshot[FutInfo.session]]
      assert(snapshot.data.size == 2)
    }

    "handle new data" in {
      val repository = TestFSMRef(Repository[FutInfo.session](), "Repository")
      val underlying = repository.underlyingActor.asInstanceOf[Repository[FutInfo.session]]

      repository ! SubscribeSnapshots(self)

      repository ! TnBegin
      assert(repository.stateName == RepositoryState.Synchronizing)

      // Add two records
      repository ! StreamData(1, Session(1, 1, 0, 100).getData)
      repository ! StreamData(1, Session(2, 2, 0, 101).getData)
      assert(underlying.storage.size == 2)

      // Delete one of them
      repository ! StreamData(1, Session(1, 3, 1, 100).getData)
      assert(underlying.storage.size == 1)

      // Then insert another one
      repository ! StreamData(1, Session(3, 4, 0, 102).getData)
      assert(underlying.storage.size == 2)

      // Close transaction
      repository ! TnCommit
      assert(repository.stateName == RepositoryState.Consistent)
      expectMsgType[Snapshot[FutInfo.session]]

      // Remove old data
      repository ! TnBegin
      repository ! ClearDeleted(1, 4)
      repository ! TnCommit
      log.info("Data: " + underlying.storage)
      assert(underlying.storage.size == 1)
    }
  }

}