package com.ergodicity.engine.core

import org.joda.time.Interval
import org.scala_tools.time.Implicits._
import akka.actor.{ActorRef, Props, Actor, FSM}
import com.ergodicity.engine.plaza2.scheme.FutInfo._
import akka.actor.FSM._

sealed trait SessionState

object SessionState {
  def apply(state: Long) = state match {
    case 0 => Assigned
    case 1 => Online
    case 2 => Suspended
    case 3 => Canceled
    case 4 => Completed
  }

  case object Assigned extends SessionState

  case object Online extends SessionState

  case object Suspended extends SessionState

  case object Canceled extends SessionState

  case object Completed extends SessionState

}

sealed trait IntClearingState

object IntClearingState {
  private val UndefinedMask = 0x0
  private val OncomingMask = 0x01
  private val CanceledMask = 0x02
  private val RunningMask = 0x04
  private val FinalizingMask = 0x08
  private val CompletedMask = 0x10

  def apply(state: Long) = state match {
    case UndefinedMask => Undefined
    case i if (i & OncomingMask) != 0 => Oncoming
    case i if (i & CanceledMask) != 0 => Canceled
    case i if (i & RunningMask) != 0 => Running
    case i if (i & FinalizingMask) != 0 => Finalizing
    case i if (i & CompletedMask) != 0 => Completed
  }

  case object Undefined extends IntClearingState

  case object Oncoming extends IntClearingState

  case object Canceled extends IntClearingState

  case object Running extends IntClearingState

  case object Finalizing extends IntClearingState

  case object Completed extends IntClearingState

}


case class IntClearing(state: IntClearingState) extends Actor with FSM[IntClearingState, Unit] {

  import IntClearingState._

  startWith(state, ())

  when(Undefined) {
    handleState
  }
  when(Oncoming) {
    handleState
  }
  when(Canceled) {
    handleState
  }
  when(Running) {
    handleState
  }
  when(Finalizing) {
    handleState
  }
  when(Completed) {
    handleState
  }

  onTransition {
    case from -> to => log.info("Intermediate clearing updated from " + from + " -> " + to)
  }

  initialize

  private def handleState: StateFunction = {
    case Event(s: IntClearingState, _) => goto(s)
  }
}

case class SessionContent(id: Long, optionsSessionId: Long, primarySession: Interval, eveningSession: Option[Interval], morningSession: Option[Interval], positionTransfer: Interval) {
  def this(rec: SessionRecord) = this(
    rec.sessionId,
    rec.optionsSessionId,
    parseInterval(rec.begin, rec.end),
    if (rec.eveOn != 0) Some(parseInterval(rec.eveBegin, rec.eveEnd)) else None,
    if (rec.monOn != 0) Some(parseInterval(rec.monBegin, rec.monEnd)) else None,
    TimeFormat.parseDateTime(rec.posTransferBegin) to TimeFormat.parseDateTime(rec.posTransferEnd)
  )
}


object Session {
  def apply(rec: SessionRecord) = {
    new Session(
      new SessionContent(rec),
      SessionState(rec.state),
      IntClearingState(rec.interClState)
    )
  }
}

case class Session(content: SessionContent, state: SessionState, intClearingState: IntClearingState) extends Actor with FSM[SessionState, ActorRef] {

  import SessionState._

  val intClearing = context.actorOf(Props(new IntClearing(intClearingState)), "IntClearing")

  startWith(state, intClearing)

  when(Assigned) {
    handleSessionState orElse handleClearingState
  }
  when(Online) {
    handleSessionState orElse handleClearingState
  }
  when(Suspended) {
    handleSessionState orElse handleClearingState
  }

  when(Canceled) {
    case Event(SessionState.Canceled, _) => stay()
    case Event(e, _) => stop(Failure("Unexpected event after canceled: " + e))
  }

  when(Canceled) {
    handleClearingState
  }

  when(Completed) {
    case Event(SessionState.Completed, _) => stay()
    case Event(e, _) => stop(Failure("Unexpected event after completion: " + e))
  }

  when(Completed) {
    handleClearingState
  }

  onTransition {
    case from -> to => log.info("Session updated from " + from + " -> " + to)
  }

  initialize

  log.info("Created session; Id = " + content.id + "; State = " + state + "; content = " + content)

  private def handleSessionState: StateFunction = {
    case Event(state: SessionState, _) => goto(state)
  }

  private def handleClearingState: StateFunction = {
    case Event(state: IntClearingState, clearing) => clearing ! state; stay()
  }
}

