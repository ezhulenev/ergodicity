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
  case class Snapshot[T](internal: Seq[T])
}

class Repository[T <: Record](implicit deserializer: Deserializer[T]) extends Actor with FSM[RepositoryState, Seq[T]] {

  var snapshotSubscribers: Seq[ActorRef] = Seq()
  
  startWith(Idle, Seq())
  
  when(Idle) {
    case Event(StreamDatumDeleted(_, _), _) => stay()
    case Event(StreamDataBegin, _) => goto(Synchronizing)
  }

  when(Consistent) {
    case Event(StreamDatumDeleted(_, rev), seq) => stay() using seq.filterNot {_.replRev < rev}
    case Event(StreamDataBegin, _) => goto(Synchronizing)    
  }

  when(Synchronizing) {
    case Event(StreamDataInserted(_, p2Record), seq) => stay() using deserializer(p2Record) +: seq
    case Event(StreamDataDeleted(_, id, _), seq) => stay() using seq.filterNot {_.replID == id}

    case Event(StreamDataEnd, _) => goto(Consistent)
  }

  onTransition {
    case Idle -> Synchronizing => log.info("Begin initializing repository")

    case Consistent -> Synchronizing => log.info("Begin updating repository")

    case Synchronizing -> Consistent =>
      log.info("Completed Plaza2 transaction")
      snapshotSubscribers.foreach {
        _ ! Snapshot(stateData)
      }
  }

  whenUnhandled {
    case Event(SubscribeSnapshots(ref), _) => snapshotSubscribers = ref +: snapshotSubscribers; stay();
  }

  initialize
}