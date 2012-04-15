package com.ergodicity.engine.plaza2

import com.ergodicity.engine.plaza2.RepositoryState.{Idle, Synchronizing, Consistent}
import scheme.{Deserializer, Record}
import plaza2.{DataStream => _, _}
import akka.actor.{ActorRef, FSM, Actor}
import com.ergodicity.engine.plaza2.Repository.{Snapshot, SubscribeSnapshots}

sealed trait RepositoryState
object RepositoryState {
  case object Idle extends RepositoryState
  case object Consistent extends RepositoryState
  case object Synchronizing extends RepositoryState
}

object Repository {
  def apply[T <: Record](implicit deserializer: Deserializer[T]) = new Repository[T]

  case class SubscribeSnapshots(ref: ActorRef)
  case class Snapshot[T <% Record](repository: ActorRef, data: Iterable[T]) {
    def filter(p: T => Boolean) = Snapshot(repository, data.filter(p))
  }
}

class Repository[T <: Record](implicit deserializer: Deserializer[T]) extends Actor with FSM[RepositoryState, Map[Long,  T]] {

  var snapshotSubscribers: Seq[ActorRef] = Seq()
  
  startWith(Idle, Map())
  
  when(Idle) {
    case Event(StreamDatumDeleted(_, _), _) => stay()
    case Event(StreamDataBegin, _) => goto(Synchronizing)
  }

  when(Consistent) {
    case Event(StreamDatumDeleted(_, rev), map) => stay() using map.filterNot{_._2.replRev < rev}
    case Event(StreamDataBegin, _) => goto(Synchronizing)

    case Event(SubscribeSnapshots(ref), _) =>
      snapshotSubscribers = ref +: snapshotSubscribers; ref ! Snapshot(self, stateData.values); stay();
  }

  when(Synchronizing) {
    case Event(event@StreamDataInserted(_, p2Record), map) =>
      log.info("RepoReplId = "+p2Record.getLong("replID"));
      val record = deserializer(p2Record)
      log.info("GOT EVENT = "+event+"; SIZE = "+map.size+"; replID = "+record.replID)
      stay() using map + (record.replID -> record)

    case Event(StreamDataDeleted(_, id, _), map) => stay() using map - id

    case Event(StreamDataEnd, _) => goto(Consistent)
  }

  whenUnhandled {
    case Event(SubscribeSnapshots(ref), _) => snapshotSubscribers = ref +: snapshotSubscribers; stay();
  }

  onTransition {
    case Idle -> Synchronizing => log.info("Begin initializing repository")

    case Consistent -> Synchronizing => log.info("Begin updating repository")

    case Synchronizing -> Consistent =>
      log.info("Completed Plaza2 transaction; Repository size = "+stateData.size)
      snapshotSubscribers.foreach {_ ! Snapshot(self, stateData.values)}
  }

  initialize
}