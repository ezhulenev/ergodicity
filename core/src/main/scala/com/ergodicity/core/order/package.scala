package com.ergodicity.core

import com.ergodicity.cgate.scheme.{OrdLog, OrdBook, OptOrder, FutOrder}

package object order {

  private def mapOrderType(orderType: Int) = orderType match {
    case t if ((t & 0x01) > 0) => OrderType.GoodTillCancelled
    case t if ((t & 0x02) > 0) => OrderType.ImmediateOrCancel
    case t => throw new IllegalArgumentException("Illegal order type: " + t)
  }

  private def mapOrderDirection(direction: Short) = direction match {
    case 1 => OrderDirection.Buy
    case 2 => OrderDirection.Sell
    case _ => throw new IllegalArgumentException("Illegal order direction: " + direction)
  }

  implicit def convertFutOrder(record: FutOrder.orders_log) = Order(record.get_id_ord(),
    record.get_sess_id(),
    IsinId(record.get_isin_id()),
    mapOrderType(record.get_status()),
    mapOrderDirection(record.get_dir()),
    record.get_price(),
    record.get_amount()
  )

  implicit def convertOptOrder(record: OptOrder.orders_log) = Order(record.get_id_ord(),
    record.get_sess_id(),
    IsinId(record.get_isin_id()),
    mapOrderType(record.get_status()),
    mapOrderDirection(record.get_dir()),
    record.get_price(),
    record.get_amount()
  )

  implicit def convertOrderBook(record: OrdBook.orders) = Order(record.get_id_ord(),
    record.get_sess_id(),
    IsinId(record.get_isin_id()),
    mapOrderType(record.get_status()),
    mapOrderDirection(record.get_dir()),
    record.get_price(),
    record.get_amount()
  )

  implicit def convertOrderLog(record: OrdLog.orders_log) = Order(record.get_id_ord(),
    record.get_sess_id(),
    IsinId(record.get_isin_id()),
    mapOrderType(record.get_status()),
    mapOrderDirection(record.get_dir()),
    record.get_price(),
    record.get_amount()
  )
}