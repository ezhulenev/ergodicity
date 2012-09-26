package com.ergodicity.capture

import org.slf4j.LoggerFactory
import com.ergodicity.cgate.scheme._
import com.ergodicity.capture.CaptureSchema._
import org.squeryl.PrimitiveTypeMode._
import com.ergodicity.core.SessionId


class MarketCaptureRepository {
  val log = LoggerFactory.getLogger(classOf[MarketCaptureRepository])
}

class MarketDbRepository extends MarketCaptureRepository with ReplicationStateRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository

trait SessionRepository {
  this: MarketCaptureRepository =>

  def session(id: SessionId): Option[Session] = sessions.lookup(compositeKey(id.fut, id.opt))

  def saveSession(record: FutInfo.session) {
    val s = Session(record)
    sessions.insertOrUpdate(sessions.lookup(compositeKey(s.sess_id, s.opt_sess_id)) getOrElse s)
  }
}

trait FutSessionContentsRepository {
  this: MarketCaptureRepository =>

  def futureContents(sessionId: Int) = from(futSessContents)(f => where(f.sess_id === sessionId) select (f)).iterator

  def saveSessionContents(record: FutInfo.fut_sess_contents) {
    val f = FutSessContents(record)
    futSessContents.insertOrUpdate(futSessContents.lookup(compositeKey(f.sess_id, f.isin_id)) getOrElse f)
  }
}

trait OptSessionContentsRepository {
  this: MarketCaptureRepository =>

  def optionContents(sessionId: Int) = from(optSessContents)(o => where(o.sess_id === sessionId) select (o)).iterator

  def saveSessionContents(record: OptInfo.opt_sess_contents) {
    val o = OptSessContents(record)
    optSessContents.insertOrUpdate(optSessContents.lookup(compositeKey(o.sess_id, o.isin_id)) getOrElse o)
  }
}


trait ReplicationStateRepository {
  this: MarketCaptureRepository =>

  def setReplicationState(stream: String, state: String) {
    val r = new ReplicationState(stream, state)
    // Hack: https://www.assembla.com/spaces/squeryl/tickets/55-keyedentity-with-def-id-refering-to-another-field-incorrectly-assumes-a-composite-key#/activity/ticket:55
    val updated = update(replicationStates)(s =>
      where(s.stream === stream)
        set (s.state := state)
    )
    if (updated == 0) replicationStates.insert(r)
  }

  def reset(stream: String) {
    replicationStates.deleteWhere(s => s.stream === stream)
  }

  def replicationState(stream: String): Option[String] = {
    log.trace("Get replicationState; Stream = " + stream)
    from(replicationStates)(s => where(s.stream === stream) select (s)).iterator.toStream.headOption.map(_.state)
  }
}