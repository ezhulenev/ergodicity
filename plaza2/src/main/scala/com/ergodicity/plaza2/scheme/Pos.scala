package com.ergodicity.plaza2.scheme

import com.twitter.ostrich.stats.Stats
import plaza2.{Record => P2Record}

object Pos {
  implicit val PositionDeserializer = new Deserializer[PositionRecord] {
    def apply(record: P2Record) = Stats.timeNanos("PositionDeserializer") {
      PositionRecord(
        record.getLong("replID"), record.getVariant("replRev").getLong, record.getLong("replAct"),

        record.getInt("isin_id"),
        record.getString("client_code"),
        record.getInt("open_qty"),
        record.getInt("buys_qty"),
        record.getInt("sells_qty"),
        record.getInt("pos"),
        record.getVariant("net_volume_rur").getDecimal,
        record.getLong("last_deal_id"),
        record.getVariant("waprice").getDecimal
      )
    }
  }

  case class PositionRecord(replID: Long, replRev: Long, replAct: Long,
                            isin_id: Int,
                            client_code: String,
                            open_qty: Int,
                            buys_qty: Int,
                            sells_qty: Int,
                            pos: Int,
                            net_volume_rur: BigDecimal,
                            last_deal_id: Long,
                            waprice: BigDecimal) extends Record
}
