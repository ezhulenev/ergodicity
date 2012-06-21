package com.ergodicity.plaza2.scheme.common

import com.ergodicity.plaza2.scheme.{Deserializer, Record}
import plaza2.{Record => P2Record}


object OrderLogRecord {
   implicit val OrdersLogDeserializer = new Deserializer[OrderLogRecord] {
    def apply(record: P2Record) = OrderLogRecord(
      record.getLong("replID"), record.getLong("replRev"), record.getLong("replAct"),

      record.getVariant("id_ord").getLong,
      record.getInt("sess_id"),
      record.getString("client_code"),
      record.getString("moment"),
      record.getInt("status"),
      record.getShort("action"),
      record.getInt("isin_id"),
      record.getShort("dir"),
      record.getVariant("price").getDecimal,
      record.getInt("amount"),
      record.getInt("amount_rest"),
      record.getInt("ext_id"),
      record.getString("comment"),
      record.getString("date_exp"),
      record.getVariant("id_ord1").getLong,
      record.getLong("id_deal"),
      record.getVariant("deal_price").getDecimal
    )
  }
}

case class OrderLogRecord(replID: Long, replRev: Long, replAct: Long,

                          id_ord: Long,
                          sess_id: Int,
                          client_code: String,
                          moment: String,
                          status: Int,
                          action: Short,
                          isin_id: Int,
                          dir: Short,
                          price: BigDecimal,
                          amount: Int,
                          amount_rest: Int,
                          ext_id: Int,
                          comment: String,
                          date_exp: String,
                          id_ord1: Long,
                          id_deal: Long,
                          deal_price: BigDecimal) extends Record