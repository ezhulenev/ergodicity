package com.ergodicity.engine.plaza2

import plaza2.Record

trait Serializer[T] {
  def apply(obj: T): Record
}

trait Deserializer[T] {
  def apply(record: Record): T
}

object Replica {
  def apply(record: Record) = new Replica(record.getLong("replID"), record.getLong("replRev"), record.getLong("replAct"))
}
case class Replica(replID: Long, replRev: Long, replAct: Long)

object Protocol {


  implicit val SessionDeserializer = new Deserializer[SessionRecord] {
    def apply(record: Record) = SessionRecord(
      Replica(record),
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

case class SessionRecord(repl: Replica,
                         sessionId: Long,
                         begin: String,
                         end: String,
                         state: Long,
                         optionSessionId: Long,
                         interClBegin: String,
                         interClEnd: String,
                         interClState: Long,
                         eveOn: Long,
                         eveBegin: String,
                         eveEnd: String,
                         monOn: Long,
                         monBegin: String,
                         monEnd: String,
                         posTransferBegin: String,
                         posTransferEnd: String)