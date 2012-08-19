package com.ergodicity.core.session

import akka.actor.{Actor, FSM}
import akka.actor.FSM.Failure
import com.ergodicity.core.Security
import com.ergodicity.core.session.Instrument.IllegalLifeCycleEvent

sealed trait InstrumentState

object InstrumentState {

  def apply(sessionState: SessionState) = sessionState match {
    case SessionState.Assigned => Assigned
    case SessionState.Online => Online
    case SessionState.Suspended => Suspended
    case SessionState.Canceled => Canceled
    case SessionState.Completed => Completed
  }

  def apply(state: Long) = state match {
    case 0 => Assigned
    case 1 => Online
    case 2 => Suspended
    case 3 => Canceled
    case 4 => Completed
    case 5 => Suspended
  }

  case object Assigned extends InstrumentState

  case object Online extends InstrumentState

  case object Canceled extends InstrumentState

  case object Completed extends InstrumentState

  case object Suspended extends InstrumentState

}
object Instrument {
  case class IllegalLifeCycleEvent(msg: String, event: Any) extends IllegalArgumentException
}

case class Instrument[S <: Security](underlyingSecurity: S, state: InstrumentState) extends Actor with FSM[InstrumentState, Unit] {

  import InstrumentState._

  startWith(state, ())

  when(Assigned) {
    handleInstrumentState
  }

  when(Online) {
    handleInstrumentState
  }

  when(Canceled) {
    case Event(Canceled, _) => stay()
    case Event(e, _) => throw new IllegalLifeCycleEvent("Unexpected event after cancellation",  e)
  }

  when(Completed) {
    case Event(Completed, _) => stay()
    case Event(e, _) => throw new IllegalLifeCycleEvent("Unexpected event after completion",  e)
  }

  when(Suspended) {
    handleInstrumentState
  }

  onTransition {
    case from -> to => log.info("Instrument updated from " + from + " -> " + to)
  }

  initialize

  log.info("Created instrument; State = " + state + "; security = " + underlyingSecurity)

  private def handleInstrumentState: StateFunction = {
    case Event(state: InstrumentState, _) => goto(state)
  }
}
