package com.ergodicity.core

import common.{BasicSecurity, FullIsin, FutureContract, OptionContract}
import com.ergodicity.cgate._
import scheme.{OptInfo, FutInfo}

package object session {
  type SessContents = {def get_isin_id(): Int; def get_isin(): String; def get_short_isin(): String}
  type StatefulSessContents = SessContents {def get_state(): Int}
  type StatelessSessContents = SessContents

  def isFuture(record: FutInfo.fut_sess_contents) = {
    import com.ergodicity.cgate.Signs
    val signs = Signs(record.get_signs())
    !signs.spot && !signs.moneyMarket && signs.anonymous
  }

  def record2isin(record: SessContents) = FullIsin(record.get_isin_id(), record.get_isin().trim, record.get_short_isin().trim)

  implicit val FutureConverter = (record: FutInfo.fut_sess_contents) => new FutureContract(record2isin(record), record.get_name().trim)

  implicit val OptionConverter = (record: OptInfo.opt_sess_contents) => new OptionContract(record2isin(record), record.get_name().trim)
}
