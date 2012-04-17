package com.ergodicity.engine.plaza2.scheme

import plaza2.{Record => P2Record}

object Part {
  implicit val PartDeserializer = new Deserializer[PartRecord] {
    def apply(record: P2Record) = {
      val money = Money(
        record.getVariant("money_old").getDecimal,
        record.getVariant("money_amount").getDecimal,
        record.getVariant("money_free").getDecimal,
        record.getVariant("money_blocked").getDecimal
      )

      val pledge = Pledge(
        record.getVariant("pledge_old").getDecimal,
        record.getVariant("pledge_amount").getDecimal,
        record.getVariant("pledge_free").getDecimal,
        record.getVariant("pledge_blocked").getDecimal
      )

      PartRecord(
        record.getLong("replID"), record.getLong("replRev"), record.getLong("replAct"),

        record.getString("client_code"),
        record.getVariant("coeff_go").getDecimal,
        record.getVariant("coeff_liquidity").getDecimal,
        money,
        pledge,
        record.getVariant("vm_reserve").getDecimal,
        record.getVariant("vm_intercl").getDecimal,
        record.getVariant("fee").getDecimal,
        record.getVariant("fee_reserve").getDecimal,
        record.getVariant("limit_spot_buy").getDecimal,
        record.getVariant("limit_spot_buy_used").getDecimal,
        record.getLong("is_auto_update_limit"),
        record.getLong("is_auto_update_spot_limit"),
        record.getLong("no_fut_discount"),
        record.getLong("limits_set"),
        record.getVariant("premium").getDecimal,
        record.getVariant("premium_order_reserve").getDouble,
        record.getVariant("balance_money").getDecimal,
        record.getVariant("vm_order_reserve").getDouble)
    }
  }

  /**
   * @param old d26.2 Денег на начало сессии
   * @param amount d26.2 Всего денег
   * @param free d26.2 Свободно денег
   * @param blocked d26.2 Заблокировано денег
   */
  case class Money(old: BigDecimal, amount: BigDecimal, free: BigDecimal, blocked: BigDecimal)

  /**
   * @param old d26.2 Залогов на начало сессии
   * @param amount d26.2 Всего залогов
   * @param free d26.2 Свободно залогов
   * @param blocked d26.2 Заблокировано залогов
   */
  case class Pledge(old: BigDecimal, amount: BigDecimal, free: BigDecimal, blocked: BigDecimal)

  /**
   * @param replID i8 Служебное поле подсистемы репликации
   * @param replRev i8 Служебное поле подсистемы репликации
   * @param replAct i8 Служебное поле подсистемы репликации
   * @param client_code c7 Код клиента
   * @param coeff_go d16.5 Коэффициент клиентского ГО
   * @param coeff_liquidity d16.5 Коэффициент ликвидности
   * @param money Money
   * @param pledge Pledge
   * @param vm_reserve d26.2 Сумма, зарезервированная под отрицательную ВМ по закрытым позициям
   * @param vm_intercl d26.2 Вариационная маржа, списанная или полученная в пром. клиринг
   * @param fee d26.2 Списанный сбор
   * @param fee_reserve d26.2 Заблокированный резерв сбора под заявки
   * @param limit_spot_buy d26.2 Лимит на Покупку Спотов
   * @param limit_spot_buy_used d26.2 Использованный Лимит на Покупку Спотов
   * @param is_auto_update_limit i1 Признак автоматической коррекции лимита на величину дохода при закачке после клиринга: 0-нет, 1-менять.
   * @param is_auto_update_spot_limit i1 Признак автоматической коррекции лимитов по Спотам (на Продажу, и на Покупку) при закачке после клиринга: 0- нет, 1-менять
   * @param no_fut_discount i1 Флаг запрещения использования скидки по фьючерсам: 1- Запрет, 0-нет
   * @param limits_set i1 Наличие установленных денежного и залогового лимитов
   * @param premium d26.2 Премия
   * @param premium_order_reserve f Резерв премии под заявки
   * @param balance_money d26.2 Сальдо денежных торговых переводов за текущую сессию
   * @param vm_order_reserve f Сумма, зарезервированная под отрицательную ВМ по заявкам
   */
  case class PartRecord(replID: Long, replRev: Long, replAct: Long,
                        client_code: String,
                        coeff_go: BigDecimal,
                        coeff_liquidity: BigDecimal,
                        money: Money,
                        pledge: Pledge,
                        vm_reserve: BigDecimal,
                        vm_intercl: BigDecimal,
                        fee: BigDecimal,
                        fee_reserve: BigDecimal,
                        limit_spot_buy: BigDecimal,
                        limit_spot_buy_used: BigDecimal,
                        is_auto_update_limit: Long,
                        is_auto_update_spot_limit: Long,
                        no_fut_discount: Long,
                        limits_set: Long,
                        premium: BigDecimal,
                        premium_order_reserve: Double,
                        balance_money: BigDecimal,
                        vm_order_reserve: Double) extends Record

}


