package com.ergodicity.engine.plaza2.scheme

import plaza2.{Record => P2Record}


object OptTrade {

  implicit val OrdersLogDeserializer = new Deserializer[OrdersLogRecord] {
    def apply(record: P2Record) = OrdersLogRecord(
      record.getLong("replID"), record.getLong("replRev"), record.getLong("replAct"),

      record.getVariant("id_ord").getLong,
      record.getLong("sess_id"),
      record.getLong("client_code"),
      record.getString("moment"),
      record.getLong("status"),
      record.getLong("action"),
      record.getLong("isin_id"),
      record.getLong("dir"),
      record.getVariant("price").getDecimal,
      record.getLong("amount"),
      record.getLong("amount_rest"),
      record.getLong("ext_id"),
      record.getString("comment"),
      record.getString("date_exp"),
      record.getVariant("id_ord1").getLong,
      record.getLong("id_deal"),
      record.getVariant("deal_price").getDecimal
    )
  }

  case class OrdersLogRecord(replID: Long, replRev: Long, replAct: Long,

                             id_ord: Long,
                             sess_id: Long,
                             client_code: Long,
                             moment: String,
                             status: Long,
                             action: Long,
                             isin_id: Long,
                             dir: Long,
                             price: BigDecimal,
                             amount: Long,
                             amount_rest: Long,
                             ext_id: Long,
                             comment: String,
                             date_exp: String,
                             id_ord1: Long,
                             id_deal: Long,
                             deal_price: BigDecimal) extends Record

  implicit val DealDeserializer = new Deserializer[DealRecord] {
    def apply(record: P2Record) = DealRecord(
      record.getLong("replID"), record.getLong("replRev"), record.getLong("replAct"),

      record.getLong("id_deal"),
      record.getLong("sess_id"),
      record.getLong("isin_id"),
      record.getVariant("price").getDecimal,
      record.getLong("amount"),
      record.getString("moment"),
      record.getVariant("id_ord_sell").getLong,
      record.getLong("status_sell"),
      record.getVariant("id_ord_buy").getLong,
      record.getLong("status_buy"),
      record.getLong("pos"),
      record.getLong("nosystem"))
  }


  case class DealRecord(replID: Long, replRev: Long, replAct: Long,

                        id_deal: Long,
                        sess_id: Long,
                        isin_id: Long,
                        price: BigDecimal,
                        amount: Long,
                        moment: String,
                        id_ord_sell: Long,
                        status_sell: Long,
                        id_ord_buy: Long,
                        status_buy: Long,
                        pos: Long,
                        nosystem: Long) extends Record

}