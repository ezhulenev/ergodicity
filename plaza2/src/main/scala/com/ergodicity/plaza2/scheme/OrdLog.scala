package com.ergodicity.plaza2.scheme

import plaza2.{Record => P2Record}
import com.twitter.ostrich.stats.Stats

object OrdLog {

  implicit val OrdersLogDeserializer = new Deserializer[OrdersLogRecord] {
    def apply(record: P2Record) = Stats.timeNanos("OrdersLogDeserializer") {
      OrdersLogRecord(
        record.getLong("replID"), record.getLong("replRev"), record.getLong("replAct"),

        record.getVariant("id_ord").getLong,
        record.getInt("sess_id"),
        record.getString("moment"),
        record.getInt("status"),
        record.getShort("action"),
        record.getInt("isin_id"),
        record.getShort("dir"),
        record.getVariant("price").getDecimal,
        record.getInt("amount"),
        record.getInt("amount_rest"),
        record.getLong("id_deal"),
        record.getVariant("deal_price").getDecimal
      )
    }
  }

  /**
   * @param replID i8 Служебное поле подсистемы репликации
   * @param replRev i8 Служебное поле подсистемы репликации
   * @param replAct i8 Служебное поле подсистемы репликации
   * @param id_ord i8 Номер заявки
   * @param sess_id i4 Идентификатор торговой сессии
   * @param moment t Время изменения состояния заявки
   * @param status i4 Статус заявки
   * @param action i1 Действие с заявкой
   * @param isin_id i4 Уникальный числовой идентификатор инструмента
   * @param dir i1 Направление
   * @param price d16.5 Цена
   * @param amount i4 Количество в операции
   * @param amount_rest i4 Оставшееся количество в заявке
   * @param id_deal i8 Идентификатор сделки по данной записи журнала заявок
   * @param deal_price d16.5 Цена заключенной сделки
   */
  case class OrdersLogRecord(replID: Long, replRev: Long, replAct: Long,

                             id_ord: Long,
                             sess_id: Int,
                             moment: String,
                             status: Int,
                             action: Short,
                             isin_id: Int,
                             dir: Short,
                             price: BigDecimal,
                             amount: Int,
                             amount_rest: Int,
                             id_deal: Long,
                             deal_price: BigDecimal) extends Record
}