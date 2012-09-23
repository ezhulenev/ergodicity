package com.ergodicity.core

import com.ergodicity.cgate.scheme.{OrdLog, OptOrder, FutOrder}

package object order {

  def direction(direction: Short) = direction match {
    case 1 => OrderDirection.Buy
    case 2 => OrderDirection.Sell
    case _ => throw new IllegalArgumentException("Illegal order direction: " + direction)
  }

  implicit def convertFutOrder(record: FutOrder.orders_log) = Order(record.get_id_ord(),
    record.get_sess_id(),
    IsinId(record.get_isin_id()),
    direction(record.get_dir()),
    record.get_price(),
    record.get_amount(),
    record.get_status()
  )

  implicit def convertOptOrder(record: OptOrder.orders_log) = Order(record.get_id_ord(),
    record.get_sess_id(),
    IsinId(record.get_isin_id()),
    direction(record.get_dir()),
    record.get_price(),
    record.get_amount(),
    record.get_status()
  )

  implicit def convertOrderLog(record: OrdLog.orders_log) = Order(record.get_id_ord(),
    record.get_sess_id(),
    IsinId(record.get_isin_id()),
    direction(record.get_dir()),
    record.get_price(),
    record.get_amount(),
    record.get_status()
  )
}