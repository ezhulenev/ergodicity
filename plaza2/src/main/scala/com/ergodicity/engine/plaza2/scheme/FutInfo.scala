package com.ergodicity.engine.plaza2.scheme

import plaza2.{Record => P2Record}
import org.joda.time.format.DateTimeFormat
import org.scala_tools.time.Implicits._

object FutInfo {
  val TimeFormat = DateTimeFormat.forPattern("yyyy/MM/dd HH:mm:ss.SSS")

  def parseInterval(begin: String, end: String) = {
    TimeFormat.parseDateTime(begin) to TimeFormat.parseDateTime(end)
  }

  implicit val SessionDeserializer = new Deserializer[SessionRecord] {
    def apply(record: P2Record) = SessionRecord(
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

case class SessionRecord(replID: Long, replRev: Long, replAct: Long,
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
                         posTransferEnd: String) extends Record