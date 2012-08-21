package com.ergodicity.core.session

import akka.actor.{LoggingFSM, Actor}
import com.ergodicity.core.Security
import com.ergodicity.core.session.InstrumentData.Limits

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

object InstrumentData {
  case class Limits(lower: BigDecimal, upper: BigDecimal)
}

case class InstrumentData(underlying: Security, limits: Limits)

object Instrument {
  case class IllegalLifeCycleEvent(msg: String, event: Any) extends IllegalArgumentException
}

class Instrument(initialState: InstrumentState, data: InstrumentData) extends Actor with LoggingFSM[InstrumentState, Unit] {

  import Instrument._
  import InstrumentState._

  override def preStart() {
    log.info("Started instrument in state = " + initialState + "; security = " + data.underlying)
    super.preStart()
  }

  startWith(initialState, ())

  when(Assigned) {
    handleInstrumentState
  }

  when(Online) {
    handleInstrumentState
  }

  when(Canceled) {
    case Event(Canceled, _) => stay()
    case Event(e, _) => throw new IllegalLifeCycleEvent("Unexpected event after cancellation", e)
  }

  when(Completed) {
    case Event(Completed, _) => stay()
    case Event(e, _) => throw new IllegalLifeCycleEvent("Unexpected event after completion", e)
  }

  when(Suspended) {
    handleInstrumentState
  }

  initialize

  private def handleInstrumentState: StateFunction = {
    case Event(state: InstrumentState, _) => goto(state)
  }
}
