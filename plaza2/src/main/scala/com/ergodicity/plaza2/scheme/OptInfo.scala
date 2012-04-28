package com.ergodicity.plaza2.scheme

import plaza2.{Record => P2Record}


object OptInfo {

  implicit val SessContentsDeserializer = new Deserializer[SessContentsRecord] {
    def apply(record: P2Record) = SessContentsRecord(
      record.getLong("replID"), record.getLong("replRev"), record.getLong("replAct"),

      record.getLong("sess_id"),
      record.getInt("isin_id"),
      record.getString("short_isin"),
      record.getString("isin"),
      record.getString("name"),
      record.getInt("signs")
    )
  }
  case class SessContentsRecord(replID: Long, replRev: Long, replAct: Long,
                                sessionId: Long,
                                isinId: Int,
                                shortIsin: String,
                                isin: String,
                                name: String,
                                signs: Int) extends Record
}

