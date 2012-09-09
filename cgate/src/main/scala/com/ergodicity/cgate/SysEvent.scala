package com.ergodicity.cgate

sealed trait SysEvent {
  def eventId: Long

  def sessionId: Long
}

object SysEvent {

  type SysEventT = {def get_event_id(): Long; def get_sess_id(): Int; def get_event_type(): Int; def get_message(): String}

  def apply(event: SysEventT) = event.get_event_type() match {
    case 1 => SessionDataReady(event.get_event_id(), event.get_sess_id())
    case 2 => IntradayClearingFinished(event.get_event_id(), event.get_sess_id())
    case _ => UnknownEvent(event.get_event_id(), event.get_sess_id(), event.get_message())
  }

  case class SessionDataReady(eventId: Long, sessionId: Int) extends SysEvent

  case class IntradayClearingFinished(eventId: Long, sessionId: Int) extends SysEvent

  case class UnknownEvent(eventId: Long, sessionId: Int, message: String) extends SysEvent

}
