package com.ergodicity.core

import com.ergodicity.plaza2.scheme.FutInfo.{Signs, SessContentsRecord}
import com.ergodicity.plaza2.scheme.{OptInfo, Record, FutInfo}
import common.{BasicSecurity, FutureContract, OptionContract}

package object session {
  type SessContents = Record {def isin: String}
  type StatefulSessContents = SessContents {def state: Long}
  type StatelessSessContents = SessContents

  def isFuture(record: SessContentsRecord) = {
    val signs = Signs(record.signs)
    !signs.spot && !signs.moneyMarket && signs.anonymous
  }

  implicit val BasicFutInfoConverter = (record: FutInfo.SessContentsRecord) => new BasicSecurity(record.isinId, record.isin.trim)

  implicit val BasicOptInfoConverter = (record: OptInfo.SessContentsRecord) => new BasicSecurity(record.isinId, record.isin.trim)

  implicit val FutureConverter = (record: FutInfo.SessContentsRecord) => new FutureContract(record.isin.trim, record.shortIsin.trim, record.isinId, record.name.trim)

  implicit val OptionConverter = (record: OptInfo.SessContentsRecord) => new OptionContract(record.isin.trim, record.shortIsin.trim, record.isinId, record.name.trim)
}
