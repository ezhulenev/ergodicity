package com.ergodicity.engine.plaza2

import com.ergodicity.engine.plaza2.RepositoryState.{Synchronizing, Snapshot}
import protocol.{Deserializer, Record}
import plaza2.{DataStream => _, _}
import akka.actor.{Props, FSM, Actor}

sealed trait RepositoryState
object RepositoryState {
  case object Snapshot extends RepositoryState
  case object Synchronizing extends RepositoryState
}

object Repository {
  def apply[T <: Record](implicit deserializer: Deserializer[T]) = new Repository[T]
}

class Repository[T <: Record](implicit deserializer: Deserializer[T]) extends Actor with FSM[RepositoryState, Seq[T]] {
  
  startWith(Snapshot, Seq())
  
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
    case Snapshot -> Synchronizing    => log.info("Begin receiving Plaza2 transaction")
    case Synchronizing -> Snapshot    => log.info("Plaza2 transaction received; Data = "+stateData)
  }

  initialize
}