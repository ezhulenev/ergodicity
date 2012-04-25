package com.ergodicity.engine.core

import com.ergodicity.engine.plaza2.scheme.FutInfo.{Signs, SessContentsRecord}
import com.ergodicity.engine.plaza2.scheme.{OptInfo, Record, FutInfo}

package object model {
  type SessContents          = Record {def isin: String}
  type StatefulSessContents  = SessContents {def state : Long}
  type StatelessSessContents = SessContents

  def isFuture(record: SessContentsRecord) = {
    val signs = Signs(record.signs)
    !signs.spot && !signs.moneyMarket && signs.anonymous
  }

  implicit val FutureConverter = (record: FutInfo.SessContentsRecord) => new FutureContract(record.isin.trim, record.shortIsin.trim, record.isinId, record.name.trim)

  implicit val OptionConverter = (record: OptInfo.SessContentsRecord) => new OptionContract(record.isin.trim, record.shortIsin.trim, record.isinId, record.name.trim)
}
