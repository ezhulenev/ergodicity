package com.ergodicity.engine.plaza2.protocol

import plaza2.{Record => P2Record}

object FutInfo {
  implicit val SessionDeserializer = new Deserializer[Session] {
    def apply(record: P2Record) = Session(
      record.getLong("replID"), record.getLong("replRev"), record.getLong("replAct"),

      record.getLong("sess_id"),
      record.getString("begin"),
      record.getString("end"),
      record.getLong("state"),
      record.getLong("opt_sess_id"),
      record.getString("inter_cl_begin"),
      record.getString("inter_cl_end"),
      record.getLong("inter_cl_state"),
      record.getLong("eve_on"),
      record.getString("eve_begin"),
      record.getString("eve_end"),
      record.getLong("mon_on"),
      record.getString("mon_begin"),
      record.getString("mon_end"),
      record.getString("pos_transfer_begin"),
      record.getString("pos_transfer_end")
    )
  }
}

case class Session(replID: Long,  replRev: Long,  replAct: Long,
                   sessionId: Long, begin: String, end: String, state: Long, optionSessionId: Long, interClBegin: String, interClEnd: String,
                   interClState: Long, eveOn: Long, eveBegin: String, eveEnd: String, monOn: Long, monBegin: String, monEnd: String,
                   posTransferBegin: String, posTransferEnd: String) extends Record