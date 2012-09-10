package com.ergodicity.core

import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}
import com.ergodicity.core.session.SessionState

object Mocking {

  def mockFutSysEvent(eventId: Long, eventType: Int, sessionId: Int) = {
    val buffer = ByteBuffer.allocate(1000)

    val sysEvent = new FutInfo.sys_events(buffer)
    sysEvent.set_event_id(eventId)
    sysEvent.set_event_type(eventType)
    sysEvent.set_sess_id(sessionId)

    sysEvent
  }

  def mockOptSysEvent(eventId: Long, eventType: Int, sessionId: Int) = {
    val buffer = ByteBuffer.allocate(1000)

    val sysEvent = new OptInfo.sys_events(buffer)
    sysEvent.set_event_id(eventId)
    sysEvent.set_event_type(eventType)
    sysEvent.set_sess_id(sessionId)

    sysEvent
  }

  def mockSession(sessionId: Int, sessionState: SessionState) = {
    val buffer = ByteBuffer.allocate(1000)

    val stateValue = SessionState.decode(sessionState)

    val session = new FutInfo.session(buffer)
    session.set_replAct(0)
    session.set_sess_id(sessionId)
    session.set_begin(0l)
    session.set_end(0l)
    session.set_opt_sess_id(sessionId)
    session.set_state(stateValue)
    session
  }

  def mockFuture(sessionId: Int, isinId: Int, isin: String, shortIsin: String, name: String, signs: Int, state: Int, multileg_type: Int = 0) = {
    val buffer = ByteBuffer.allocate(1000)
    val fut = new FutInfo.fut_sess_contents(buffer)
    fut.set_sess_id(sessionId)
    fut.set_isin_id(isinId)
    fut.set_short_isin(shortIsin)
    fut.set_isin(isin)
    fut.set_name(name)
    fut.set_signs(signs)
    fut.set_state(state)
    fut.set_multileg_type(multileg_type)
    fut
  }

  def mockOption(sessionId: Int, isinId: Int, isin: String, shortIsin: String, name: String, signs: Int) = {
    val buffer = ByteBuffer.allocate(1000)
    val opt = new OptInfo.opt_sess_contents(buffer)
    opt.set_sess_id(sessionId)
    opt.set_isin_id(isinId)
    opt.set_isin(isin)
    opt.set_short_isin(shortIsin)
    opt.set_name(name)
    opt.set_signs(signs)
    opt
  }
}