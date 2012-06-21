package com.ergodicity.plaza2.scheme.common;

import com.ergodicity.plaza2.scheme.{Deserializer, Record}
import plaza2.{Record => P2Record}

object DealRecord {
  implicit val DealDeserializer = new Deserializer[DealRecord] {
    def apply(record: P2Record) = DealRecord(
      record.getLong("replID"), record.getLong("replRev"), record.getLong("replAct"),

      record.getLong("id_deal"),
      record.getInt("sess_id"),
      record.getInt("isin_id"),
      record.getVariant("price").getDecimal,
      record.getInt("amount"),
      record.getString("moment"),
      record.getVariant("id_ord_sell").getLong,
      record.getInt("status_sell"),
      record.getVariant("id_ord_buy").getLong,
      record.getInt("status_buy"),
      record.getInt("pos"),
      record.getShort("nosystem"))

  }
}

case class DealRecord(replID: Long, replRev: Long, replAct: Long,

                      id_deal: Long,
                      sess_id: Int,
                      isin_id: Int,
                      price: BigDecimal,
                      amount: Int,
                      moment: String,
                      id_ord_sell: Long,
                      status_sell: Int,
                      id_ord_buy: Long,
                      status_buy: Int,
                      pos: Int,
                      nosystem: Short) extends Record