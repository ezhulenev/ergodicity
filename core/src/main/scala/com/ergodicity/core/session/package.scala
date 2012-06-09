package com.ergodicity.core

import com.ergodicity.plaza2.scheme.FutInfo.{Signs, SessContentsRecord}
import com.ergodicity.plaza2.scheme.{OptInfo, Record, FutInfo}
import common.{Isin, BasicSecurity, FutureContract, OptionContract}

package object session {
  type SessContents = Record {def isinId: Int; def isin: String; def shortIsin: String}
  type StatefulSessContents = SessContents {def state: Long}
  type StatelessSessContents = SessContents

  def isFuture(record: SessContentsRecord) = {
    val signs = Signs(record.signs)
    !signs.spot && !signs.moneyMarket && signs.anonymous
  }

  def record2isin(record: SessContents) = Isin(record.isinId, record.isin.trim, record.shortIsin.trim)

  implicit val BasicFutInfoConverter = (record: FutInfo.SessContentsRecord) => new BasicSecurity(record2isin(record))

  implicit val BasicOptInfoConverter = (record: OptInfo.SessContentsRecord) => new BasicSecurity(record2isin(record))

  implicit val FutureConverter = (record: FutInfo.SessContentsRecord) => new FutureContract(record2isin(record), record.name.trim)

  implicit val OptionConverter = (record: OptInfo.SessContentsRecord) => new OptionContract(record2isin(record), record.name.trim)
}
