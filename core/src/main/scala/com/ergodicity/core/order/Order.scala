package com.ergodicity.core.order

import com.ergodicity.core.{OrderDirection, OrderType, IsinId}
import com.ergodicity.cgate.scheme.{OrdLog, OrdBook, OptOrder, FutOrder}

case class Order(id: Long, sessionId: Int, isin: IsinId, orderType: OrderType, direction: OrderDirection, price: BigDecimal, amount: Int)

case class OrderAction(orderId: Long, action: Action)

sealed trait Action

case class Create(order: Order) extends Action

case class Cancel(amount: Int) extends Action

case class Fill(amount: Int, rest: Int, deal: Option[(Long, BigDecimal)]) extends Action

object Action {
  def apply(record: FutOrder.orders_log) = record.get_action() match {
    case 0 => Cancel(record.get_amount())
    case 1 => Create(record)
    case 2 => Fill(record.get_amount(), record.get_amount_rest(), Some(record.get_id_deal, record.get_deal_price()))
    case _ => throw new IllegalArgumentException("Illegal 'FutOrder.orders_log' action: " + record.get_action())
  }

  def apply(record: OptOrder.orders_log) = record.get_action() match {
    case 0 => Cancel(record.get_amount())
    case 1 => Create(record)
    case 2 => Fill(record.get_amount(), record.get_amount_rest(), Some(record.get_id_deal, record.get_deal_price()))
    case _ => throw new IllegalArgumentException("Illegal 'OptOrder.orders_log' action: " + record.get_action())
  }

  def apply(record: OrdBook.orders) = record.get_action() match {
    case 1 => Create(record)
    case 2 => Fill(record.get_amount(), record.get_amount_rest(), None)
    case _ => throw new IllegalArgumentException("Illegal 'OrdBook.orders' action: " + record.get_action())
  }

  def apply(record: OrdLog.orders_log) = record.get_action() match {
    case 0 => Cancel(record.get_amount())
    case 1 => Create(record)
    case 2 => Fill(record.get_amount(), record.get_amount_rest(), Some(record.get_id_deal, record.get_deal_price()))
    case _ => throw new IllegalArgumentException("Illegal 'OrdLog.orders_log' action: " + record.get_action())
  }
}