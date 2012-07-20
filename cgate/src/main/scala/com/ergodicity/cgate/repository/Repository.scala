package com.ergodicity.cgate.repository

import akka.actor.{ActorRef, FSM, Actor}
import com.ergodicity.cgate.repository.Repository.{SubscribeSnapshots, Snapshot}
import akka.actor.FSM.Failure

sealed trait RepositoryState

object RepositoryState {

  case object Consistent extends RepositoryState

  case object Synchronizing extends RepositoryState

}

object Repository {
  def apply[T]()(implicit reads: Reads[T], replica: ReplicaExtractor[T]) = new Repository[T]

  case class SubscribeSnapshots(ref: ActorRef)

  case class Snapshot[T](repository: ActorRef, data: Iterable[T]) {
    def filter(p: T => Boolean) = Snapshot(repository, data.filter(p))
  }

}

class Repository[T](implicit reads: Reads[T], replica: ReplicaExtractor[T]) extends Actor with FSM[RepositoryState, Map[Long, T]] {


  import RepositoryState._
  import com.ergodicity.cgate.StreamEvent._

  var snapshotSubscribers: Set[ActorRef] = Set()

  startWith(Consistent, Map())

  when(Consistent) {
    case Event(SubscribeSnapshots(ref), _) =>
      snapshotSubscribers = snapshotSubscribers + ref
      ref ! Snapshot(self, stateData.values)
      stay()

    case Event(LifeNumChanged(_), map) =>
      snapshotSubscribers.foreach(_ ! Snapshot[T](self, Seq()))
      stay() using Map()

    case Event(TnBegin, _) => goto(Synchronizing)
  }


  when(Synchronizing) {
    case Event(SubscribeSnapshots(ref), _) =>
      snapshotSubscribers = snapshotSubscribers + ref
      stay();

    case Event(StreamData(_, data), map) =>
      val rec = reads(data)
      val repl = replica(rec)

      if (repl.replID == repl.replAct) {
        // Delete record
        stay() using map - repl.replID
      } else stay() using map + (repl.replID -> rec)

    case Event(TnCommit, _) => goto(Consistent)

    case Event(ClearDeleted(_, rev), map) => stay() using map.filterNot {
      case (id, rec) =>
        replica(rec).replRev < rev
    }
  }

  whenUnhandled {
    case Event(e, _) => stop(Failure("Unexpected event = " + e))
  }

  onTransition {
    case Consistent -> Synchronizing => log.info("Begin updating repository")

    case Synchronizing -> Consistent =>
      log.info("Completed transaction; Repository size = " + stateData.size)
      snapshotSubscribers.foreach {
        _ ! Snapshot(self, stateData.values)
      }
  }

  initialize
}
