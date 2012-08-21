package com.ergodicity.cgate.sysevents

sealed trait SysEvent {
  def sessionId: Long
}

object SysEvent {

  type SysEventType ={def get_sess_id(): Int; def get_event_type(): Int; def get_message(): String}

  def apply(event: SysEventType) = event.get_event_type() match {
    case 1 => SessionDataReady(event.get_sess_id())
    case 2 => IntradayClearingFinished(event.get_sess_id())
    case _ => UnknownEvent(event.get_sess_id(), event.get_message())
  }

  case class SessionDataReady(sessionId: Long) extends SysEvent

  case class IntradayClearingFinished(sessionId: Long) extends SysEvent

  case class UnknownEvent(sessionId: Long, message: String) extends SysEvent

}