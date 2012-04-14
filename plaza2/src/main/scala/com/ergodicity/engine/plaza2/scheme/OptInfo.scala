package com.ergodicity.engine.plaza2.scheme

import plaza2.{Record => P2Record}


object OptInfo {

  implicit val SessContentsDeserializer = new Deserializer[SessContentsRecord] {
    def apply(record: P2Record) = SessContentsRecord(
      record.getLong("replID"), record.getLong("replRev"), record.getLong("replAct"),

      record.getLong("sess_id"),
      record.getLong("isin_id"),
      record.getString("short_isin"),
      record.getString("isin"),
      record.getString("name"),
      record.getLong("signs")
    )
  }
  case class SessContentsRecord(replID: Long, replRev: Long, replAct: Long,
                                sessId: Long,
                                isinId: Long,
                                shortIsin: String,
                                isin: String,
                                name: String,
                                signs: Long) extends Record

}

