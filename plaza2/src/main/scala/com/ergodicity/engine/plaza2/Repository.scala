package com.ergodicity.engine.plaza2

import com.ergodicity.engine.plaza2.RepositoryState.{Idle, Synchronizing, Snapshot}
import scheme.{Deserializer, Record}
import plaza2.{DataStream => _, _}
import akka.actor.{FSM, Actor}

sealed trait RepositoryState
object RepositoryState {
  case object Idle extends RepositoryState
  case object Snapshot extends RepositoryState
  case object Synchronizing extends RepositoryState
}

object Repository {
  def apply[T <: Record](implicit deserializer: Deserializer[T]) = new Repository[T]
}

class Repository[T <: Record](implicit deserializer: Deserializer[T]) extends Actor with FSM[RepositoryState, Seq[T]] {
  
  startWith(Idle, Seq())
  
  when(Idle) {
    case Event(StreamDatumDeleted(_, _), _) => stay()
    case Event(StreamDataBegin, _) => goto(Synchronizing)
  }

  when(Snapshot) {
    case Event(StreamDatumDeleted(_, rev), seq) => stay() using seq.filterNot {_.replRev < rev}
    case Event(StreamDataBegin, _) => goto(Synchronizing)
  }
  
  when(Synchronizing) {
    case Event(StreamDataInserted(_, p2Record), seq) => stay() using deserializer(p2Record) +: seq
    case Event(StreamDataDeleted(_, id, _), seq) => stay() using seq.filterNot {_.replID == id}

    case Event(StreamDataEnd, _) => goto(Snapshot)
  }

  onTransition {
    case Idle -> Synchronizing        => log.info("Begin initializing repository")
    case Snapshot -> Synchronizing    => log.info("Begin updating repository")
    case Synchronizing -> Snapshot    => log.info("Completed Plaza2 transaction; Data = "+stateData)
  }

  initialize
}