package com.ergodicity.capture

import org.squeryl.annotations.Column
import org.squeryl.KeyedEntity
import org.squeryl.PrimitiveTypeMode._
import org.squeryl.Schema
import org.squeryl.dsl.CompositeKey2
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}


object Session {
  def apply(record: FutInfo.session) = new Session(
    record.get_sess_id(),
    record.get_begin(),
    record.get_end(),
    record.get_opt_sess_id(),
    record.get_inter_cl_begin(),
    record.get_inter_cl_end(),
    record.get_eve_on(),
    record.get_eve_begin(),
    record.get_eve_end(),
    record.get_mon_on(),
    record.get_mon_begin(),
    record.get_mon_end(),
    record.get_pos_transfer_begin(),
    record.get_pos_transfer_end()
  )
}

class Session(val sess_id: Int,
              val begin: Long,
              val end: Long,
              val opt_sess_id: Int,
              val inter_cl_begin: Long,
              val inter_cl_end: Long,
              val eve_on: Int,
              val eve_begin: Long,
              val eve_end: Long,
              val mon_on: Int,
              val mon_begin: Long,
              val mon_end: Long,
              val pos_transfer_begin: Long,
              val pos_transfer_end: Long) extends KeyedEntity[CompositeKey2[Int, Int]] {
  def id = compositeKey(sess_id, opt_sess_id)

  override def toString = "Session(sess_id=" + sess_id + ", opt_sess_id=" + opt_sess_id + ")"
}

object FutSessContents {
  def apply(record: FutInfo.fut_sess_contents) = new FutSessContents(
    record.get_sess_id(),
    record.get_isin_id(),
    record.get_short_isin(),
    record.get_isin(),
    record.get_name(),
    record.get_inst_term(),
    record.get_code_vcb(),
    record.get_is_limited(),
    record.get_limit_up(),
    record.get_limit_down(),
    record.get_old_kotir(),
    record.get_buy_deposit(),
    record.get_sell_deposit(),
    record.get_roundto(),
    record.get_min_step(),
    record.get_lot_volume(),
    record.get_step_price(),
    record.get_d_pg(),
    record.get_is_spread(),
    record.get_coeff(),
    record.get_d_exp(),
    record.get_is_percent(),
    record.get_percent_rate(),
    record.get_last_cl_quote(),
    record.get_signs(),
    record.get_is_trade_evening(),
    record.get_ticker(),
    record.get_price_dir(),
    record.get_multileg_type(),
    record.get_legs_qty(),
    record.get_step_price_clr(),
    record.get_step_price_interclr(),
    record.get_step_price_curr(),
    record.get_d_start()
  )
}

class FutSessContents(val sess_id: Int,
                      val isin_id: Int,
                      val short_isin: String,
                      val isin: String,
                      val name: String,
                      val inst_term: Int,
                      val code_vcb: String,
                      val is_limited: Int,
                      val limit_up: BigDecimal,
                      val limit_down: BigDecimal,
                      val old_kotir: BigDecimal,
                      val buy_deposit: BigDecimal,
                      val sell_deposit: BigDecimal,
                      val roundto: Int,
                      val min_step: BigDecimal,
                      val lot_volume: Int,
                      val step_price: BigDecimal,
                      val d_pg: Long,
                      val is_spread: Int,
                      val coeff: BigDecimal,
                      val d_exp: Long,
                      val is_percent: Int,
                      val percent_rate: BigDecimal,
                      val last_cl_quote: BigDecimal,
                      val signs: Int,
                      val is_trade_evening: Int,
                      val ticker: Int,
                      val price_dir: Int,
                      val multileg_type: Int,
                      val legs_qty: Int,
                      val step_price_clr: BigDecimal,
                      val step_price_interclr: BigDecimal,
                      val step_price_curr: BigDecimal,
                      val d_start: Long) extends KeyedEntity[CompositeKey2[Int, Int]] {
  def id = compositeKey(sess_id, isin_id)
}

object OptSessContents {
  def apply(record: OptInfo.opt_sess_contents) = new OptSessContents(
    record.get_sess_id(),
    record.get_isin_id(),
    record.get_isin(),
    record.get_short_isin(),
    record.get_name(),
    record.get_code_vcb(),
    record.get_fut_isin_id(),
    record.get_is_limited(),
    record.get_limit_up(),
    record.get_limit_down(),
    record.get_old_kotir(),
    record.get_bgo_c(),
    record.get_bgo_nc(),
    record.get_europe(),
    record.get_put(),
    record.get_strike(),
    record.get_roundto(),
    record.get_min_step(),
    record.get_lot_volume(),
    record.get_step_price(),
    record.get_d_pg(),
    record.get_d_exec_beg(),
    record.get_d_exec_end(),
    record.get_signs(),
    record.get_last_cl_quote(),
    record.get_bgo_buy(),
    record.get_base_isin_id(),
    record.get_d_start()
  )
}

class OptSessContents(val sess_id: Int,
                      val isin_id: Int,
                      val isin: String,
                      val short_isin: String,
                      val name: String,
                      val code_vcb: String,
                      val fut_isin_id: Int,
                      val is_limited: Int,
                      val limit_up: BigDecimal,
                      val limit_down: BigDecimal,
                      val old_kotir: BigDecimal,
                      val bgo_c: BigDecimal,
                      val bgo_nc: BigDecimal,
                      val europe: Int,
                      val put: Int,
                      val strike: BigDecimal,
                      val roundto: Int,
                      val min_step: BigDecimal,
                      val lot_volume: Int,
                      val step_price: BigDecimal,
                      val d_pg: Long,
                      val d_exec_beg: Long,
                      val d_exec_end: Long,
                      val signs: Int,
                      val last_cl_quote: BigDecimal,
                      val bgo_buy: BigDecimal,
                      val base_isin_id: Int,
                      val d_start: Long) extends KeyedEntity[CompositeKey2[Int, Int]] {
  def id = compositeKey(sess_id, isin_id)
}

class ReplicationState(val stream: String,
                       @Column(length = 5000)
                       val state: String) extends KeyedEntity[String] {
  def id = stream
}

object CaptureSchema extends Schema {
  val sessions = table[Session]("SESSION")
  val futSessContents = table[FutSessContents]("FUT_SESS_CONTENTS")
  val optSessContents = table[OptSessContents]("OPT_SESS_CONTENTS")
  val replicationStates = table[ReplicationState]("REPLICATION_STATE")

  on(replicationStates)(r => declare(r.stream is (unique)))
}
