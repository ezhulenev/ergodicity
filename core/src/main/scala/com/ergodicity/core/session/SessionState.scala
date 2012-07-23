package com.ergodicity.core.session

sealed trait SessionState

object SessionState {
  def apply(state: Int) = state match {
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

  def apply(state: Int) = state match {
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