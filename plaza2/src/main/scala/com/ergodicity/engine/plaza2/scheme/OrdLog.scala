package com.ergodicity.engine.plaza2.scheme

import plaza2.{Record => P2Record}

object OrdLog {

  implicit val OrdersLogDeserializer = new Deserializer[OrdersLogRecord] {
    def apply(record: P2Record) = OrdersLogRecord(
      record.getLong("replID"), record.getLong("replRev"), record.getLong("replAct"),

      record.getVariant("id_ord").getLong,
      record.getLong("sess_id"),
      record.getString("moment"),
      record.getLong("status"),
      record.getLong("action"),
      record.getLong("isin_id"),
      record.getLong("dir"),
      record.getVariant("price").getDecimal,
      record.getLong("amount"),
      record.getLong("amount_rest"),
      record.getLong("id_deal"),
      record.getVariant("deal_price").getDecimal
    )
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
                             sess_id: Long,
                             moment: String,
                             status: Long,
                             action: Long,
                             isin_id: Long,
                             dir: Long,
                             price: BigDecimal,
                             amount: Long,
                             amount_rest: Long,
                             id_deal: Long,
                             deal_price: BigDecimal) extends Record
}