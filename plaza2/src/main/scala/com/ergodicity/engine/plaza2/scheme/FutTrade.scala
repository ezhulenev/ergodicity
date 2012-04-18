package com.ergodicity.engine.plaza2.scheme

import plaza2.{Record => P2Record}


object FutTrade {

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

  /*
  
  replID i8 Служебное поле подсистемы репликации                                                     replID i8 Служебное поле подсистемы репликации
  replRev i8 Служебное поле подсистемы репликации                                                    replRev i8 Служебное поле подсистемы репликации
  replAct i8 Служебное поле подсистемы репликации                                                    replAct i8 Служебное поле подсистемы репликации
  id_deal i8 Номер сделки                                                                            id_deal i8 Номер сделки
  sess_id i4 Идентификатор торговой сессии                                                           sess_id i4 Идентификатор торговой сессии
  isin_id i4 Уникальный числовой идентификатор инструмента                                           isin_id i4 Уникальный числовой идентификатор инструмента
  price d16.5 Цена                                                                                   price d16.5 Цена
  amount i4 Объем, кол-во единиц инструмента                                                         amount i4 Объем, кол-во единиц инструмента
  moment t Время заключения сделки                                                                   moment t Время заключения сделки
  code_sell c7 Код продавца                                                                          code_sell c7 Код продавца
  code_buy c7 Код покупателя                                                                         code_buy c7 Код покупателя
  id_ord_sell i8 Номер заявки продавца                                                               id_ord_sell i8 Номер заявки продавца
  ext_id_sell i4 Внешний номер из заявки продавца                                                    ext_id_sell i4 Внешний номер из заявки продавца
  comment_sell c20 Комментарий из заявки продавца                                                    comment_sell c20 Комментарий из заявки продавца
  trust_sell i1 Признак ДУ (доверительного управления) из заявки                                     trust_sell i1 Признак ДУ (доверительного управления) из заявки
  продавца                                                                                           продавца
  status_sell i4 Статус сделки со стороны продавца                                                   status_sell i4 Статус сделки со стороны продавца
  id_ord_buy i8 Номер заявки покупателя                                                              id_ord_buy i8 Номер заявки покупателя
  ext_id_buy i4 Внешний номер из заявки покупателя                                                   ext_id_buy i4 Внешний номер из заявки покупателя
  comment_buy c20 Комментарий из заявки покупателя                                                   comment_buy c20 Комментарий из заявки покупателя
  trust_buy i1 Признак ДУ (доверительного управления) из заявки                                      trust_buy i1 Признак ДУ (доверительного управления) из заявки
  покупателя                                                                                         покупателя
  status_buy i4 Статус сделки со стороны покупателя                                                  status_buy i4 Статус сделки со стороны покупателя
  pos i4 Кол-во позиций по инструменту на рынке после сделки                                         pos i4 Кол-во позиций по инструменту на рынке после сделки
  nosystem i1 Признак внесистемной сделки                                                            nosystem i1 Признак внесистемной сделки
  id_repo i8 Номер другой части сделки РЕПО
  hedge_sell i1 Признак хеджевой сделки со стороны продавца                                          hedge_sell i1 Признак хеджевой сделки со стороны продавца
  hedge_buy i1 Признак хеджевой сделки со стороны покупателя                                         hedge_buy i1 Признак хеджевой сделки со стороны покупателя
  fee_sell d26.2 Сбор по сделке продавца                                                             login_sell c20 Логин пользователя продавца
  fee_buy d26.2 Сбор по сделке покупателя                                                            login_buy c20 Логин пользователя покупателя
  login_sell c20 Логин пользователя продавца                                                         code_rts_buy c7 Код РТС покупателя
  login_buy c20 Логин пользователя покупателя                                                        code_rts_sell c7 Код РТС продавца
  code_rts_sell c7 Код РТС продавца                                                                  fee_sell d26.2 Сбор по сделке продавца
  code_rts_buy c7 Код РТС покупателя                                                                 fee_buy d26.2 Сбор по сделке покупателя
  id_deal_multileg i8 Номер сделки по связке                                                         id_deal_multileg i8 Номер сделки по связке

  code_sell
  comment_sell
  ext_id_sell
  trust_sell
  hedge_sell
  login_sell
  code_rts_sell
  fee_sell
  code_buy
  comment_buy
  ext_id_buy
  trust_buy
  hedge_buy
  login_buy
  code_rts_buy
  fee_buy
 

   */

  /**
   *  replID i8 Служебное поле подсистемы репликации                                                  replID i8 Служебное поле подсистемы репликации
      replRev i8 Служебное поле подсистемы репликации                                                 replRev i8 Служебное поле подсистемы репликации
      replAct i8 Служебное поле подсистемы репликации                                                 replAct i8 Служебное поле подсистемы репликации
      id_ord i8 Номер заявки                                                                          id_ord i8 Номер заявки
      sess_id i4 Идентификатор торговой сессии                                                        sess_id i4 Идентификатор торговой сессии
      client_code c7 Код клиента                                                                      client_code c7 Код клиента
      moment t Время изменения состояния заявки                                                       moment t Время изменения состояния заявки
      status i4 Статус заявки                                                                         status i4 Статус заявки
      action i1 Действие с заявкой                                                                    action i1 Действие с заявкой
      isin_id i4 Уникальный числовой идентификатор инструмента                                        isin_id i4 Уникальный числовой идентификатор инструмента
      dir i1 Направление                                                                              dir i1 Направление
      price d16.5 Цена                                                                                price d16.5 Цена
      amount i4 Количество в операции                                                                 amount i4 Количество в операции
      amount_rest i4 Оставшееся количество в заявке                                                   amount_rest i4 Оставшееся количество в заявке
      comment c20 Комментарий трейдера                                                                comment c20 Комментарий трейдера
      hedge i1 Признак хеджевой заявки                                                                hedge i1 Признак хеджевой заявки
      trust i1 Признак заявки доверительного управления                                               trust i1 Признак заявки доверительного управления
      ext_id i4 Внешний номер                                                                         ext_id i4 Внешний номер
      login_from c20 Логин пользователя, поставившего заявку                                          login_from c20 Логин пользователя, поставившего заявку
      broker_to c7 Код FORTS фирмы-адресата внесистемной заявки                                       broker_to c7 Код FORTS фирмы-адресата внесистемной заявки
      broker_to_rts c7 Код RTS фирмы-адресата внесистемной заявки                                     broker_to_rts c7 Код RTS фирмы-адресата внесистемной заявки
      date_exp t Дата истечения заявки                                                                date_exp t Дата истечения заявки
      id_ord1 i8 Номер первой заявки                                                                  id_ord1 i8 Номер первой заявки
      broker_from_rts c7 Код РТС клиента - владельца заявки                                           broker_from_rts c7 Код РТС клиента - владельца заявки
      id_deal i8 Идентификатор сделки по данной записи журнала заявок
      deal_price d16.5 Цена заключенной сделки
      local_stamp t Локальное время пользователя
   */

}