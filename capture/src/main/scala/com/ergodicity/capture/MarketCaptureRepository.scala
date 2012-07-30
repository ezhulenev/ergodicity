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
    "sessionId" -> record.get_sess_id(),
    "begin" -> record.get_begin(),
    "end" -> record.get_end(),
    "optionSessionId" -> record.get_opt_sess_id(),
    "interClBegin" -> record.get_inter_cl_begin(),
    "interClEnd" -> record.get_inter_cl_end(),
    "eveOn" -> record.get_eve_on(),
    "eveBegin" -> record.get_eve_begin(),
    "eveEnd" -> record.get_eve_end(),
    "monOn" -> record.get_mon_on(),
    "monBegin" -> record.get_mon_begin(),
    "monEnd" -> record.get_mon_end(),
    "posTransferBegin" -> record.get_pos_transfer_begin(),
    "posTransferEnd" -> record.get_pos_transfer_end()
  )
}

trait FutSessionContentsRepository {
  this: {def log: Logger; def mongo: MongoDB} =>

  val FutContents = mongo("FutSessionContents")
  FutContents.ensureIndex(MongoDBObject("sessionId" -> 1, "isinId" -> 1, "isin" -> 1), "futSessionContentsIdx", false)

  def saveSessionContents(record: FutInfo.fut_sess_contents) {
    FutContents.findOne(MongoDBObject("sessionId" -> record.get_sess_id(), "isinId" -> record.get_isin_id())) map {
      _ => () /* do nothing */
    } getOrElse {
      /* create new one */
      val contents = convert(record)
      FutContents += contents
    }
  }

  def convert(record: FutInfo.fut_sess_contents) = MongoDBObject(
    "sessionId" -> record.get_sess_id(),
    "isinId" -> record.get_isin_id(),
    "shortIsin" -> record.get_short_isin(),
    "isin" -> record.get_isin(),
    "name" -> record.get_name(),
    "signs" -> record.get_signs(),
    "multileg_type" -> record.get_multileg_type()
  )
}

trait OptSessionContentsRepository {
  this: {def log: Logger; def mongo: MongoDB} =>

  val OptContents = mongo("OptSessionContents")
  OptContents.ensureIndex(MongoDBObject("sessionId" -> 1, "isinId" -> 1, "isin" -> 1), "optSessionContentsIdx", false)

  def saveSessionContents(record: OptInfo.opt_sess_contents) {
    OptContents.findOne(MongoDBObject("sessionId" -> record.get_sess_id(), "isinId" -> record.get_isin_id())) map {
      _ => () /* do nothing */
    } getOrElse {
      /* create new one */
      val contents = convert(record)
      OptContents += contents
    }
  }

  def convert(record: OptInfo.opt_sess_contents) = MongoDBObject(
    "sessionId" -> record.get_sess_id(),
    "isinId" -> record.get_isin_id(),
    "shortIsin" -> record.get_short_isin(),
    "isin" -> record.get_isin(),
    "name" -> record.get_name(),
    "signs" -> record.get_signs()
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