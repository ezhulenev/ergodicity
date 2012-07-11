package com.ergodicity.core

import com.ergodicity.plaza2.scheme.common.OrderLogRecord
import common._
import OrderType._
import OrderDirection._

package object order {
  implicit def toOrderProps(record: OrderLogRecord) = {

    def mapOrderType(orderType: Int) = orderType match {
      case t if ((t & 0x01) > 0) => GoodTillCancelled
      case t if ((t & 0x02) > 0) => ImmediateOrCancel
    }

    def mapOrderDirection(direction: Short) = direction match {
      case 1 => Buy
      case 2 => Sell
      case _ => throw new IllegalArgumentException("Illegal order direction: " + direction)
    }

    OrderProps(record.id_ord,
      record.sess_id,
      IsinId(record.isin_id),
      mapOrderType(record.status),
      mapOrderDirection(record.dir),
      record.price,
      record.amount
    )
  }


  class OrdersTrackingException(message: String) extends RuntimeException(message)

  case class TrackSession(sessionId: Int)

  case class DropSession(sessionId: Int)


  private[order] sealed trait Action

  case object Delete

  case object Create

  case object Fill

  object Action {
    def apply(record: OrderLogRecord) = record.action match {
      case 0 => Delete
      case 1 => Create
      case 2 => Fill
      case _ => throw new IllegalArgumentException("Illegal 'orders_log' action: " + record.action)
    }
  }

}