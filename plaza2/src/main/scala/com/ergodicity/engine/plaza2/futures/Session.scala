package com.ergodicity.engine.plaza2.futures

import org.joda.time.Interval
import org.joda.time.format.DateTimeFormat
import org.scala_tools.time.Implicits._
import akka.actor.{Actor, FSM}
import com.ergodicity.engine.plaza2.protocol.{Session => SessionRecord}


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


case class SessionContent(id: Long, intClearingInterval: Interval,
                          primarySession: Interval, eveningSession: Option[Interval],
                          morningSession: Option[Interval], positionTransfer: Interval)

object SessionContent {
  def TimeFormat = DateTimeFormat.forPattern("yyyy/MM/dd HH:mm:ss.SSS")

  def apply(rec: SessionRecord) = {
    val primarySession = TimeFormat.parseDateTime(rec.begin) to TimeFormat.parseDateTime(rec.end)
    val intermediateClearingInterval = TimeFormat.parseDateTime(rec.interClBegin) to TimeFormat.parseDateTime(rec.interClEnd)
    val eveningSession = if (rec.eveOn != 0) Some(TimeFormat.parseDateTime(rec.eveBegin) to TimeFormat.parseDateTime(rec.eveEnd)) else None
    val morningSession = if (rec.monOn != 0) Some(TimeFormat.parseDateTime(rec.monBegin) to TimeFormat.parseDateTime(rec.monEnd)) else None
    val positionTransfer = TimeFormat.parseDateTime(rec.posTransferBegin) to TimeFormat.parseDateTime(rec.posTransferEnd)

    new SessionContent(rec.sessionId,
      intermediateClearingInterval,
      primarySession,
      eveningSession,
      morningSession,
      positionTransfer)
  }
}


case class Session(content: SessionContent, state: SessionState,
                   intClearingState: IntClearingState) extends Actor with FSM[SessionState, IntClearingState] {

  startWith(state, intClearingState)

  initialize
}