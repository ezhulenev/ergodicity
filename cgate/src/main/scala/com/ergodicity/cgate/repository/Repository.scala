package com.ergodicity.cgate.repository

import akka.actor.{LoggingFSM, ActorRef, Actor}
import com.ergodicity.cgate.repository.Repository.{GetSnapshot, IllegalLifeCycleEvent, SubscribeSnapshots, Snapshot}
import com.ergodicity.cgate.Reads
import collection.mutable

sealed trait RepositoryState

object RepositoryState {

  case object Consistent extends RepositoryState

  case object Synchronizing extends RepositoryState

}

case class RepositorySubscribers(private val subscribers: Seq[ActorRef] = Seq(), private val pending: Seq[ActorRef] = Seq()) {
  def dispatch(snapshot: Snapshot[_]): RepositorySubscribers = {
    subscribers foreach (_ ! snapshot)
    pending foreach (_ ! snapshot)
    copy(pending = Seq())
  }

  def subscribe(ref: ActorRef) = copy(subscribers = this.subscribers :+ ref)

  def append(ref: ActorRef) = copy(pending = this.pending :+ ref)
}

object Repository {
  def apply[T]()(implicit reads: Reads[T], replica: ReplicaExtractor[T]) = new Repository[T]

  case object GetSnapshot

  case class SubscribeSnapshots(ref: ActorRef)

  case class Snapshot[T](repository: ActorRef, data: Iterable[T]) {
    def filter(p: T => Boolean) = Snapshot(repository, data.filter(p))
  }

  case class IllegalLifeCycleEvent(msg: String, event: Any) extends IllegalArgumentException

}

class Repository[T](implicit reads: Reads[T], replica: ReplicaExtractor[T]) extends Actor with LoggingFSM[RepositoryState, RepositorySubscribers] {

  import RepositoryState._
  import com.ergodicity.cgate.StreamEvent._

  val storage = mutable.Map[Long, T]()

  startWith(Consistent, RepositorySubscribers())

  when(Consistent) {
    case Event(LifeNumChanged(_), subscribers) =>
      stay() using subscribers.dispatch(Snapshot[T](self, Seq()))

    case Event(GetSnapshot, map) =>
      sender ! Snapshot[T](self, storage.values)
      stay()

    case Event(TnBegin, _) => goto(Synchronizing)
  }


  when(Synchronizing) {
    case Event(SubscribeSnapshots(ref), subscribers) =>
      stay() using subscribers.subscribe(ref)

    case Event(GetSnapshot, subscribers) =>
      stay() using subscribers.append(sender)

    case Event(StreamData(_, data), _) =>
      val rec = reads(data)
      val repl = replica(rec)

      if (repl.replID == repl.replAct) {
        // Delete record
        storage.remove(repl.replID)
      } else {
        // Add or update record
        storage(repl.replID) = rec
      }
      stay()

    case Event(TnCommit, subscribers) =>
      goto(Consistent) using subscribers.dispatch(Snapshot(self, storage.values))
  }

  whenUnhandled {
    case Event(SubscribeSnapshots(ref), subscribers) =>
      ref ! Snapshot(self, storage.values)
      stay() using subscribers.subscribe(ref)

    case Event(ClearDeleted(_, rev), map) =>
      storage.retain {
        case (id, rec) => replica(rec).replRev >= rev
      }
      stay()

    case Event(e, _) => throw new IllegalLifeCycleEvent("Unexpected event in state = " + stateName, e)
  }

  onTransition {
    case Consistent -> Synchronizing => log.info("Begin updating repository")

    case Synchronizing -> Consistent => log.info("Completed transaction; Repository size = " + storage.size)
  }

  initialize
}
