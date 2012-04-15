package com.ergodicity.engine.core

import com.ergodicity.engine.plaza2.scheme.FutInfo
import com.ergodicity.engine.plaza2.scheme.FutInfo.{Signs, SessContentsRecord}

package object model {
  def isFuture(record: SessContentsRecord) = {
    val signs = Signs(record.signs)
    !signs.spot && !signs.moneyMarket && signs.anonymous
  }

  def FutureConverter = (record: FutInfo.SessContentsRecord) => new Future(record.isin, record.shortIsin, record.isinId, record.name)

}
