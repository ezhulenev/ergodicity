package com.ergodicity.backtest

import com.ergodicity.schema.{FutSessContents, ErgodicitySchema}
import org.joda.time.Interval
import org.squeryl.PrimitiveTypeMode._


class MarketRepository {
  def scan(interval: Interval) =  {
    val sessions = from(ErgodicitySchema.sessions)(s => where((s.begin gte interval.getStart.getMillis) and (s.end lte interval.getEnd.getMillis)) select (s)).iterator.toSeq
    val futures = sessions.map(session => from(ErgodicitySchema.futSessContents)(f => where((f.sess_id === session.sess_id)) select(f)).iterator.toSeq)
    val options = sessions.map(session => from(ErgodicitySchema.optSessContents)(o => where((o.sess_id === session.sess_id)) select(o)).iterator.toSeq)

    (sessions, futures, options).zipped.toList
  }
}