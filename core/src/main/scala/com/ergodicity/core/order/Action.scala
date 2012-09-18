package com.ergodicity.core.order

import com.ergodicity.cgate.scheme.{OrdLog, OrdBook, OptOrder, FutOrder}

sealed trait Action

case class Create(order: Order) extends Action

case class Delete(orderId: Long, amount: Int) extends Action

case class Fill(orderId: Long, deal: Long, amount: Int, price: BigDecimal, rest: Int) extends Action

object Action {
  def apply(record: FutOrder.orders_log) = record.get_action() match {
    case 0 => Delete(record.get_id_ord(), record.get_amount())
    case 1 => Create(record)
    case 2 => Fill(record.get_id_ord(), record.get_id_deal(), record.get_amount(), record.get_deal_price(), record.get_amount_rest())
    case _ => throw new IllegalArgumentException("Illegal 'orders_log' action: " + record.get_action())
  }

  def apply(record: OptOrder.orders_log) = record.get_action() match {
    case 0 => Delete(record.get_id_ord(), record.get_amount())
    case 1 => Create(record)
    case 2 => Fill(record.get_id_ord(), record.get_id_deal(), record.get_amount(), record.get_deal_price(), record.get_amount_rest())
    case _ => throw new IllegalArgumentException("Illegal 'orders_log' action: " + record.get_action())
  }

  def apply(record: OrdBook.orders) = record.get_action() match {
    case 1 => Create(record)
    case _ => throw new IllegalArgumentException("Illegal 'OrderBook' action: " + record.get_action())
  }

  def apply(record: OrdLog.orders_log) = record.get_action() match {
    case 0 => Delete(record.get_id_ord(), record.get_amount())
    case 1 => Create(record)
    case 2 => Fill(record.get_id_ord(), record.get_id_deal(), record.get_amount(), record.get_deal_price(), record.get_amount_rest())
    case _ => throw new IllegalArgumentException("Illegal 'orders_log' action: " + record.get_action())
  }
}