package com.ergodicity.capture

import org.slf4j.{Logger, LoggerFactory}
import com.mongodb.casbah.Imports._
import com.ergodicity.cgate.scheme._


class MarketCaptureRepository(database: CaptureDatabase) {
  val log = LoggerFactory.getLogger(classOf[MarketCaptureRepository])

  val mongo = database()
}

class MarketDbRepository(database: CaptureDatabase) extends MarketCaptureRepository(database) with ReplicationStateRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository

trait SessionRepository {
  this: {def log: Logger; def mongo: MongoDB} =>

  val Sessions = mongo("Sessions")
  Sessions.ensureIndex(MongoDBObject("sessionId" -> 1), "sessionIdIndex", false)

  def saveSession(record: FutInfo.session) {
    log.trace("Save session = " + record)

    Sessions.findOne(MongoDBObject("sessionId" -> record.get_sess_id())) map {
      _ => () /* do nothing */
    } getOrElse {
      /* create new one */
      val session = convert(record)
      Sessions += session
    }
  }

  def convert(record: FutInfo.session) = MongoDBObject(
    "sess_id" -> record.get_sess_id(),
    "begin" -> record.get_begin(),
    "end" -> record.get_end(),
    "state" -> record.get_state(),
    "opt_sess_id" -> record.get_opt_sess_id(),
    "inter_cl_begin" -> record.get_inter_cl_begin(),
    "inter_cl_end" -> record.get_inter_cl_end(),
    "inter_cl_state" -> record.get_inter_cl_state(),
    "eve_on" -> record.get_eve_on(),
    "eve_begin" -> record.get_eve_begin(),
    "eve_end" -> record.get_eve_end(),
    "mon_on" -> record.get_mon_on(),
    "mon_begin" -> record.get_mon_begin(),
    "mon_end" -> record.get_mon_end(),
    "pos_transfer_begin" -> record.get_pos_transfer_begin(),
    "pos_transfer_end" -> record.get_pos_transfer_end()
  )
}

trait FutSessionContentsRepository {
  this: {def log: Logger; def mongo: MongoDB} =>

  val FutContents = mongo("FutSessionContents")
  FutContents.ensureIndex(MongoDBObject("sess_id" -> 1, "isin_id" -> 1), "futSessionContentsIdx", false)

  def saveSessionContents(record: FutInfo.fut_sess_contents) {
    FutContents.findOne(MongoDBObject("sess_id" -> record.get_sess_id(), "isin_id" -> record.get_isin_id())) map {
      _ => () /* do nothing */
    } getOrElse {
      /* create new one */
      val contents = convert(record)
      FutContents += contents
    }
  }

  def convert(record: FutInfo.fut_sess_contents) = MongoDBObject(
    "sess_id" -> record.get_sess_id(),
    "isin_id" -> record.get_isin_id(),
    "short_isin" -> record.get_short_isin(),
    "isin" -> record.get_isin(),
    "name" -> record.get_name(),
    "inst_term" -> record.get_inst_term(),
    "code_vcb" -> record.get_code_vcb(),
    "is_limited" -> record.get_is_limited(),
    "limit_up" -> record.get_limit_up(),
    "limit_down" -> record.get_limit_down(),
    "old_kotir" -> record.get_old_kotir(),
    "buy_deposit" -> record.get_buy_deposit(),
    "sell_deposit" -> record.get_sell_deposit(),
    "roundto" -> record.get_roundto(),
    "min_step" -> record.get_min_step(),
    "lot_volume" -> record.get_lot_volume(),
    "step_price" -> record.get_step_price(),
    "d_pg" -> record.get_d_pg(),
    "is_spread" -> record.get_is_spread(),
    "coeff" -> record.get_coeff(),
    "d_exp" -> record.get_d_exp(),
    "is_percent" -> record.get_is_percent(),
    "percent_rate" -> record.get_percent_rate(),
    "last_cl_quote" -> record.get_last_cl_quote(),
    "signs" -> record.get_signs(),
    "is_trade_evening" -> record.get_is_trade_evening(),
    "ticker" -> record.get_ticker(),
    "price_dir" -> record.get_price_dir(),
    "multileg_type" -> record.get_multileg_type(),
    "legs_qty" -> record.get_legs_qty(),
    "step_price_clr" -> record.get_step_price_clr(),
    "step_price_interclr" -> record.get_step_price_interclr(),
    "step_price_curr" -> record.get_step_price_curr(),
    "d_start" -> record.get_d_start()
  )
}

trait OptSessionContentsRepository {
  this: {def log: Logger; def mongo: MongoDB} =>

  val OptContents = mongo("OptSessionContents")
  OptContents.ensureIndex(MongoDBObject("sess_id" -> 1, "isin_id" -> 1), "optSessionContentsIdx", false)

  def saveSessionContents(record: OptInfo.opt_sess_contents) {
    OptContents.findOne(MongoDBObject("sess_id" -> record.get_sess_id(), "isin_id" -> record.get_isin_id())) map {
      _ => () /* do nothing */
    } getOrElse {
      /* create new one */
      val contents = convert(record)
      OptContents += contents
    }
  }

  def convert(record: OptInfo.opt_sess_contents) = MongoDBObject(
    "sess_id" -> record.get_sess_id(),
    "isin_id" -> record.get_isin_id(),
    "isin" -> record.get_isin(),
    "short_isin" -> record.get_short_isin(),
    "name" -> record.get_name(),
    "code_vcb" -> record.get_code_vcb(),
    "fut_isin_id" -> record.get_fut_isin_id(),
    "is_limited" -> record.get_is_limited(),
    "limit_up" -> record.get_limit_up(),
    "limit_down" -> record.get_limit_down(),
    "old_kotir" -> record.get_old_kotir(),
    "bgo_c" -> record.get_bgo_c(),
    "bgo_nc" -> record.get_bgo_nc(),
    "europe" -> record.get_europe(),
    "put" -> record.get_put(),
    "strike" -> record.get_strike(),
    "roundto" -> record.get_roundto(),
    "min_step" -> record.get_min_step(),
    "lot_volume" -> record.get_lot_volume(),
    "step_price" -> record.get_step_price(),
    "d_pg" -> record.get_d_pg(),
    "d_exec_beg" -> record.get_d_exec_beg(),
    "d_exec_end" -> record.get_d_exec_end(),
    "signs" -> record.get_signs(),
    "last_cl_quote" -> record.get_last_cl_quote(),
    "bgo_buy" -> record.get_bgo_buy(),
    "base_isin_id" -> record.get_base_isin_id(),
    "d_start" -> record.get_d_start()
  )
}


trait ReplicationStateRepository {
  this: {def log: Logger; def mongo: MongoDB} =>

  val ReplicationStates = mongo("ReplicationStates")

  def setReplicationState(stream: String, state: String) {
    log.info("Set replication state for stream = " + stream + "; Value = " + state)
    ReplicationStates.findOne(MongoDBObject("stream" -> stream)) map {
      obj =>
        ReplicationStates.update(obj, $set("state" -> state))
    } getOrElse {
      val replicationState = MongoDBObject("stream" -> stream, "state" -> state)
      ReplicationStates += replicationState
    }
  }

  def reset(stream: String) {
    ReplicationStates.remove(MongoDBObject("stream" -> stream))
  }

  def replicationState(stream: String): Option[String] = {
    log.trace("Get replicationState; Stream = " + stream)
    ReplicationStates.findOne(MongoDBObject("stream" -> stream)).flatMap(_.getAs[String]("state"))
  }

}