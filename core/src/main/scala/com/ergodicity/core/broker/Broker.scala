package com.ergodicity.core.broker

import plaza2.{MessageFactory, Connection => P2Connection}
import org.slf4j.LoggerFactory
import com.jacob.com.Variant
import com.ergodicity.core.common._

object Broker {
  def apply(clientCode: String, connection: P2Connection)(implicit messageFactory: MessageFactory) = new Broker(clientCode, connection)
}

protected[broker] sealed trait Order {
  def id: Long
}

case class FutOrder(id: Long) extends Order

case class OptOrder(id: Long) extends Order


class Broker(clientCode: String, connection: P2Connection)(implicit messageFactory: MessageFactory) {
  private val log = LoggerFactory.getLogger(classOf[Broker])

  private val FORTS_MSG = "FORTS_MSG"
  lazy val service = connection.resolveService("FORTS_SRV")

  private def mapOrderType(orderType: OrderType) = orderType match {
    case GoodTillCancelled => 1
    case ImmediateOrCancel => 2
    case FillOrKill => 3
  }

  private def mapOrderDirection(direction: OrderDirection) = direction match {
    case Buy => 1
    case Sell => 2
  }

  private def addOrder(future: FutureContract, orderType: OrderType, direction: OrderDirection, price: BigDecimal, amount: Int): Either[String, FutOrder] = {

    val message = messageFactory.createMessage("FutAddOrder")

    message.destAddr = service.address

    message.setField("P2_Category", FORTS_MSG)
    message.setField("P2_Type", 36)

    message.setField("isin", future.isin.code)
    message.setField("price", price.toString())
    message.setField("amount", amount)
    message.setField("client_code", clientCode)
    message.setField("type", mapOrderType(orderType))
    message.setField("dir", mapOrderDirection(direction))

    val response = message.send(connection)
    val c = response.field("P2_Category").getString
    val t = response.field("P2_Type").changeType(Variant.VariantInt).getInt

    if (c == FORTS_MSG && t == 101) {
      if (response.field("code").getInt == 0) {
        Right(FutOrder(response.field("order_id").getLong))
      } else {
        Left("Adding order failed, error message = " + response.field("message"))
      }
    } else {
      Left("Adding order failed; Response Category = " + c + "; Type = " + t)
    }
  }

  def buy(future: FutureContract, orderType: OrderType, price: BigDecimal, amount: Int) = {
    log.debug("Buy: Security = " + future + "; Price = " + price + "; Amount = " + amount + "; Type = " + orderType)
    addOrder(future, orderType, Buy, price, amount)
  }

  def sell(future: FutureContract, orderType: OrderType, price: BigDecimal, amount: Int) = {
    log.debug("Sell: Security = " + future + "; Price = " + price + "; Amount = " + amount + "; Type = " + orderType)
    addOrder(future, orderType, Sell, price, amount)
  }

  def cancel(order: FutOrder): Either[String, Int] = {
    val message = messageFactory.createMessage("FutDelOrder")

    message.destAddr = service.address

    message.setField("P2_Category", FORTS_MSG)
    message.setField("P2_Type", 37)

    message.setField("order_id", order.id)

    val response = message.send(connection)
    val c = response.field("P2_Category").getString
    val t = response.field("P2_Type").changeType(Variant.VariantInt).getInt

    if (c == FORTS_MSG && t == 102) {
      val code = response.field("code").getInt
      if (code == 0) {
        Right(response.field("amount").changeType(Variant.VariantInt).getInt)
      } else if (code == 14) {
        Right(0)
      } else {
        Left("Canceling order failed, error message = " + response.field("message"))
      }
    } else {
      Left("Canceling order failed; Response Category = " + c + "; Type = " + t)
    }
  }
}