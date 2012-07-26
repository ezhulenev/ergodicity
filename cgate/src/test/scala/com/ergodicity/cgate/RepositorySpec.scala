package com.ergodicity.cgate

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import akka.actor.ActorSystem
import repository.Repository.{Snapshot, SubscribeSnapshots}
import repository.RepositoryState.Consistent
import repository.{RepositoryState, Repository}
import scheme.FutInfo
import com.ergodicity.cgate.Protocol._
import com.ergodicity.cgate.repository.ReplicaExtractor._
import java.nio.ByteBuffer
import akka.util.duration._
import com.ergodicity.cgate.StreamEvent._

class RepositorySpec extends TestKit(ActorSystem("RepositorySpec", AkkaConfigurations.ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  val Host = "host"
  val Port = 4001
  val AppName = "ConnectionSpec"

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
      assert(repository.stateName == RepositoryState.Empty)
    }

    "receive snapshot immediately in Consistent state" in {
      val repository = TestFSMRef(Repository[FutInfo.session](), "Repository")

      val map = Map(1l -> Session)
      repository.setState(Consistent, map)

      repository ! SubscribeSnapshots(self)
      val snapshot = receiveOne(1.second).asInstanceOf[Snapshot[FutInfo.session]]
      assert(snapshot.data.size == 1)
    }

    "clean on LifeNumChanged" in {
      val repository = TestFSMRef(Repository[FutInfo.session](), "Repository")

      val map = Map(1l -> Session)
      repository.setState(Consistent, map)

      repository ! SubscribeSnapshots(self)
      var snapshot = receiveOne(1.second).asInstanceOf[Snapshot[FutInfo.session]]
      assert(snapshot.data.size == 1)

      repository ! LifeNumChanged(1)
      snapshot = receiveOne(1.second).asInstanceOf[Snapshot[FutInfo.session]]
      assert(snapshot.data.size == 0)
    }

    "handle new data" in {
      val repository = TestFSMRef(Repository[FutInfo.session](), "Repository")

      repository ! SubscribeSnapshots(self)

      repository ! TnBegin
      assert(repository.stateName == RepositoryState.Synchronizing)

      // Add two records
      repository ! StreamData(1, Session(1, 1, 0, 100).getData)
      repository ! StreamData(1, Session(2, 2, 0, 101).getData)
      assert(repository.stateData.size == 2)

      // Delete one of them
      repository ! StreamData(1, Session(1, 3, 1, 100).getData)
      assert(repository.stateData.size == 1)

      // Then insert another one
      repository ! StreamData(1, Session(3, 4, 0, 102).getData)
      assert(repository.stateData.size == 2)

      // Close transaction
      repository ! TnCommit
      assert(repository.stateName == RepositoryState.Consistent)
      expectMsgType[Snapshot[FutInfo.session]]

      // Remove old data
      repository ! TnBegin
      repository ! ClearDeleted(1, 4)
      repository ! TnCommit
      log.info("Data: " + repository.stateData)
      assert(repository.stateData.size == 1)
    }
  }

}