package com.ergodicity.core

import com.ergodicity.cgate.scheme.FutTrade

package object order {

  case class TrackSession(sessionId: Int)

  case class DropSession(sessionId: Int)

  private[order] sealed trait Action

  case object Delete

  case object Create

  case object Fill

  object Action {
    def apply(record: FutTrade.orders_log) = record.get_action() match {
      case 0 => Delete
      case 1 => Create
      case 2 => Fill
      case _ => throw new IllegalArgumentException("Illegal 'orders_log' action: " + record.get_action())
    }
  }

  implicit def toOrderProps(record: FutTrade.orders_log) = {

    def mapOrderType(orderType: Int) = orderType match {
      case t if ((t & 0x01) > 0) => OrderType.GoodTillCancelled
      case t if ((t & 0x02) > 0) => OrderType.ImmediateOrCancel
      case t => throw new IllegalArgumentException("Illegal order type: " + t)
    }

    def mapOrderDirection(direction: Short) = direction match {
      case 1 => OrderDirection.Buy
      case 2 => OrderDirection.Sell
      case _ => throw new IllegalArgumentException("Illegal order direction: " + direction)
    }

    OrderProps(record.get_id_ord(),
      record.get_sess_id(),
      IsinId(record.get_isin_id()),
      mapOrderType(record.get_status()),
      mapOrderDirection(record.get_dir()),
      record.get_price(),
      record.get_amount()
    )
  }
}