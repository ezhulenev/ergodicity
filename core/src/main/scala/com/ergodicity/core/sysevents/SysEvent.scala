package com.ergodicity.core.sysevents

import com.ergodicity.cgate.scheme.FutInfo

sealed trait SysEvent {
  def sessionId: Long
}

object SysEvent {

  def apply(event: FutInfo.sys_events) = event.get_event_type() match {
    case 1 => SessionDataReady(event.get_sess_id())
    case 2 => IntradayClearingFinished(event.get_sess_id())
    case _ => UnknownEvent(event.get_sess_id(), event.get_message())
  }

  case class SessionDataReady(sessionId: Long) extends SysEvent

  case class IntradayClearingFinished(sessionId: Long) extends SysEvent

  case class UnknownEvent(sessionId: Long, message: String) extends SysEvent
}




