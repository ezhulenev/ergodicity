package com.ergodicity.core

import com.ergodicity.cgate.scheme.{OptTrade, FutTrade}

package object order {


  case class GetOrdersTracking(sessionId: Int)

  case class DropSession(sessionId: Int)

  sealed trait OrderAction

  case class Create(props: OrderProps) extends OrderAction

  case class Delete(orderId: Long, amount: Int) extends OrderAction

  case class Fill(orderId: Long, deal: Long, amount: Int, price: BigDecimal, rest: Int) extends OrderAction

  object OrderAction {
    def apply(record: FutTrade.orders_log) = record.get_action() match {
      case 0 => Delete(record.get_id_ord(), record.get_amount())
      case 1 => Create(record)
      case 2 => Fill(record.get_id_ord(), record.get_id_deal(), record.get_amount(), record.get_deal_price(), record.get_amount_rest())
      case _ => throw new IllegalArgumentException("Illegal 'orders_log' action: " + record.get_action())
    }

    def apply(record: OptTrade.orders_log) = record.get_action() match {
      case 0 => Delete(record.get_id_ord(), record.get_amount())
      case 1 => Create(record)
      case 2 => Fill(record.get_id_ord(), record.get_id_deal(), record.get_amount(), record.get_deal_price(), record.get_amount_rest())
      case _ => throw new IllegalArgumentException("Illegal 'orders_log' action: " + record.get_action())
    }
  }

  implicit def futuresToOrderProps(record: FutTrade.orders_log) = {

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

  implicit def optionsToOrderProps(record: OptTrade.orders_log) = {

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