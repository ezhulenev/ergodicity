package com.ergodicity.engine.core

import com.ergodicity.engine.plaza2.scheme.FutInfo.{Signs, SessContentsRecord}
import com.ergodicity.engine.plaza2.scheme.{Record, FutInfo}

package object model {
  type SessContents          = Record {def isin: String}
  type StatefulSessContents  = SessContents {def state : Long}
  type StatelessSessContents = SessContents

  def isFuture(record: SessContentsRecord) = {
    val signs = Signs(record.signs)
    !signs.spot && !signs.moneyMarket && signs.anonymous
  }

  implicit val FutureConverter = (record: FutInfo.SessContentsRecord) => new Future(record.isin, record.shortIsin, record.isinId, record.name)

//  val OptionConverter = (record: OptInfo.SessContentsRecord) => new Option(record.isin, record.shortIsin, record.isinId, record.name)
}
