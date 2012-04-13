package com.ergodicity.engine.plaza2.scheme

import org.joda.time.format.DateTimeFormat
import org.scala_tools.time.Implicits._
import plaza2.{Record => P2Record}

object FutInfo {
  val TimeFormat = DateTimeFormat.forPattern("yyyy/MM/dd HH:mm:ss.SSS")

  def parseInterval(begin: String, end: String) = {
    TimeFormat.parseDateTime(begin) to TimeFormat.parseDateTime(end)
  }

  implicit val SessionDeserializer = new Deserializer[SessionRecord] {
    def apply(record: P2Record) = SessionRecord(
      record.getLong("replID"), record.getLong("replRev"), record.getLong("replAct"),

      record.getLong("sess_id"),
      record.getString("begin"),
      record.getString("end"),
      record.getLong("state"),
      record.getLong("opt_sess_id"),
      record.getString("inter_cl_begin"),
      record.getString("inter_cl_end"),
      record.getLong("inter_cl_state"),
      record.getLong("eve_on"),
      record.getString("eve_begin"),
      record.getString("eve_end"),
      record.getLong("mon_on"),
      record.getString("mon_begin"),
      record.getString("mon_end"),
      record.getString("pos_transfer_begin"),
      record.getString("pos_transfer_end")
    )
  }

  implicit val SessContentsDeserializer = new Deserializer[SessContentsRecord] {
    def apply(record: P2Record) = SessContentsRecord(
      record.getLong("replID"), record.getLong("replRev"), record.getLong("replAct"),

      record.getLong("sess_id"),
      record.getLong("isin_id"),
      record.getString("short_isin"),
      record.getString("isin"),
      record.getString("name"),
      record.getLong("state")
    )
  }
}

case class SessionRecord(replID: Long, replRev: Long, replAct: Long,
                         sessionId: Long,
                         begin: String,
                         end: String,
                         state: Long,
                         optionSessionId: Long,
                         interClBegin: String,
                         interClEnd: String,
                         interClState: Long,
                         eveOn: Long,
                         eveBegin: String,
                         eveEnd: String,
                         monOn: Long,
                         monBegin: String,
                         monEnd: String,
                         posTransferBegin: String,
                         posTransferEnd: String) extends Record

case class SessContentsRecord(replID: Long, replRev: Long, replAct: Long,
                               sessId: Long,
                               isinId: Long,
                               shortIsin: String,
                               isin: String,
                               name: String,
                               state: Long) extends Record

/*
replID i8 Служебное поле подсистемы репликации
replRev i8 Служебное поле подсистемы репликации
replAct i8 Служебное поле подсистемы репликации
sess_id i4 Идентификатор торговой сессии
isin_id i4 Уникальный числовой идентификатор инструмента
short_isin c25 Описатель инструмента
isin c25 Символьный код инструмента
name c75 Наименование инструмента
inst_term i4 Смещение от спота
code_vcb c25 Код базового актива
is_limited i1 Признак наличия лимитов в торгах

limit_up d16.5 Верхний лимит цены
limit_down d16.5 Нижний лимит цены
old_kotir d16.5 Скорректированная расчетная цена предыдущей сессии
buy_deposit d16.2 ГО покупателя
sell_deposit d16.2 ГО продавца
roundto i4 Количество знаков после запятой в цене
min_step d16.5 Минимальный шаг цены
lot_volume i4 К-во единиц базового актива в инструменте
step_price d16.5 Стоимость шага цены
d_pg t Дата окончания обращения инструмента
is_spread i1 Признак вхождения фьючерса в межмесячный спрэд. 1 –
входит; 0 – не входит
coeff d9.6 Коэффициент межмесячного спрэда
d_exp t Дата исполнения инструмента
is_percent i1 Признак того, что фьючерс торгуется в процентах. 1 -
торгуется процентах, 0 – торгуется не в процентах
percent_rate d6.2 Процентная ставка для расчета вариационной маржи по
процентным фьючерсам
last_cl_quote d16.5 Котировка после последнего клиринга
signs i4 Поле признаков
is_trade_evening i1 Признак торговли в вечернюю сессию
ticker i4 Уникальный числовой код Главного Спота
state i4 Состояние торговли по инструменту
price_dir i1 Направление цены инструмента
multileg_type i4 Тип связки
legs_qty i4 Количество инструментов в связке
step_price_clr d16.5 Cтоимость шага цены вечернего клиринга
step_price_interclr d16.5 Cтоимость шага цены пром. клиринга
step_price_curr d16.5 Стоимость минимального шага цены, выраженная в
валюте
d_start t Дата ввода инструмента в обращение
*/