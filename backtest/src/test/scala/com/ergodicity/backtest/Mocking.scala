package com.ergodicity.backtest

import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}
import org.joda.time.DateTime

object Mocking {

  def mockSession(sessionId: Int, optSessionId: Int, begin: DateTime, end: DateTime) = {
    val buff = ByteBuffer.allocate(1000)
    val session = new FutInfo.session(buff)
    session.set_sess_id(sessionId)
    session.set_opt_sess_id(optSessionId)
    session.set_begin(begin.getMillis)
    session.set_end(end.getMillis)
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