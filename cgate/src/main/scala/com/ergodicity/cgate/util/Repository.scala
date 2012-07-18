package com.ergodicity.cgate.util

import akka.actor.{ActorRef, FSM, Actor}

sealed trait RepositoryState

object RepositoryState {

  case object Consistent extends RepositoryState

  case object Synchronizing extends RepositoryState

}

/*
object Repository {
  def apply[T]()(implicit reads: ReplicaExtractor[T]) = new Repository[T]

  case class SubscribeSnapshots(ref: ActorRef)

  case class Snapshot[T](repository: ActorRef, data: Iterable[T]) {
    def filter(p: T => Boolean) = Snapshot(repository, data.filter(p))
  }

}

class Repository[T](implicit reads: ReplicaExtractor[T]) extends Actor with FSM[RepositoryState, Map[Long, T]] {

  import RepositoryState._

  var snapshotSubscribers: Set[ActorRef] = Set()

  startWith(Consistent, Map())

  when(Consistent) {
    case Event(DataBegin, _) => goto(Synchronizing)

    case Event(SubscribeSnapshots(ref), _) =>
      snapshotSubscribers = snapshotSubscribers + ref; ref ! Snapshot(self, stateData.values); stay();
  }

  when(Synchronizing) {
    case Event(event@DataInserted(_, record: T), map) =>
      stay() using map + (record.replID -> record)

    case Event(DataDeleted(_, replId), map) => stay() using map - replId

    case Event(DataEnd, _) => goto(Consistent)
  }

  whenUnhandled {
    case Event(DatumDeleted(_, rev), map) => stay() using map.filterNot {
      _._2.replRev < rev
    }
    case Event(SubscribeSnapshots(ref), _) => snapshotSubscribers = snapshotSubscribers + ref; stay();
  }

  onTransition {
    case Idle -> Synchronizing => log.info("Begin initializing repository")

    case Consistent -> Synchronizing => log.info("Begin updating repository")

    case Synchronizing -> Consistent =>
      log.info("Completed Plaza2 transaction; Repository size = " + stateData.size)
      snapshotSubscribers.foreach {
        _ ! Snapshot(self, stateData.values)
      }
  }

  initialize
}*/
