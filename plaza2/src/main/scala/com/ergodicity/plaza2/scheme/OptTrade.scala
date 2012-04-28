package com.ergodicity.plaza2.scheme

import plaza2.{Record => P2Record}


object OptTrade {

  implicit val OrdersLogDeserializer = new Deserializer[OrdersLogRecord] {
    def apply(record: P2Record) = OrdersLogRecord(
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

  case class OrdersLogRecord(replID: Long, replRev: Long, replAct: Long,

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
}