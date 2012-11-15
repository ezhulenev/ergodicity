package com.ergodicity.backtest

import com.ergodicity.schema.ErgodicitySchema
import org.joda.time.Interval
import org.squeryl.PrimitiveTypeMode._


class MarketRepository {
  def sessions(interval: Interval) =  {
    from(ErgodicitySchema.sessions)(s => where((s.begin gte interval.getStart.getMillis) and (s.end lte interval.getEnd.getMillis)) select (s)).iterator.toSeq
  }
}