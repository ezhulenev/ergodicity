package com.ergodicity.core.session

import akka.actor.{FSM, LoggingFSM, Actor}
import akka.util.duration._
import com.ergodicity.core.Security
import com.ergodicity.core.session.Instrument.Limits
import akka.util.Timeout

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

  case class Limits(lower: BigDecimal, upper: BigDecimal)

}

case class Instrument(security: Security, limits: Limits)

object InstrumentActor {

  case class IllegalLifeCycleEvent(msg: String, event: Any) extends IllegalArgumentException

}

class InstrumentActor(underlying: Instrument) extends Actor with LoggingFSM[InstrumentState, Unit] {

  import InstrumentActor._
  import InstrumentState._

  override def preStart() {
    log.info("Started instrument = " + underlying)
    super.preStart()
  }

  // Start in suspended state
  startWith(Suspended, (), timeout = Some(1.second))

  when(Assigned) {
    handleInstrumentState
  }

  when(Online) {
    handleInstrumentState
  }

  when(Canceled) {
    case Event(Canceled, _) => stay()
    case Event(e, _) =>
      throw new IllegalLifeCycleEvent("Unexpected event after cancellation", e)
  }

  when(Completed) {
    case Event(Completed, _) => stay()
    case Event(e, _) =>
      throw new IllegalLifeCycleEvent("Unexpected event after completion", e)
  }

  when(Suspended) {
    handleInstrumentState
  }

  whenUnhandled {
    case Event(e@FSM.StateTimeout, _) =>
      throw new IllegalLifeCycleEvent("Timed out in initial Suspended state", e)
  }

  initialize

  private def handleInstrumentState: StateFunction = {
    case Event(state: InstrumentState, _) => goto(state)
  }
}
