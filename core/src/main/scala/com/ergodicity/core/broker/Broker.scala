package com.ergodicity.core.broker

import plaza2.{MessageFactory, Connection => P2Connection}
import com.jacob.com.Variant
import com.ergodicity.core.common._
import akka.event.Logging
import akka.actor.{Actor, ActorSystem}
import akka.dispatch.Future

object Broker {
  val FORTS_MSG = "FORTS_MSG"

  def apply(clientCode: String, connection: P2Connection)(implicit messageFactory: MessageFactory, system: ActorSystem) = new Broker(clientCode, connection)
}

protected[broker] sealed trait Order {
  def id: Long
}

case class FutOrder(id: Long) extends Order

case class OptOrder(id: Long) extends Order


protected[broker] sealed trait BrokerCommand

object BrokerCommand {
  case class Sell[S <: Security](sec: S, orderType: OrderType, price: BigDecimal, amount: Int) extends BrokerCommand

  case class Buy[S <: Security](sec: S, orderType: OrderType, price: BigDecimal, amount: Int) extends BrokerCommand

  case class Cancel[O <: Order](order: O) extends BrokerCommand
}

case class ExecutionReport[O <: Order](order: Either[String, O])

case class CancelReport(amount: Either[String,  Int])


class Broker(clientCode: String, connection: P2Connection)(implicit messageFactory: MessageFactory) extends Actor with WhenUnhandled {
  import Broker._
  import BrokerCommand._

  val log = Logging(context.system, self)

  implicit val system = context.system

  lazy val service = connection.resolveService("FORTS_SRV")

  private def handleBuyCommand: Receive = {
    case Buy(future: FutureContract, orderType, price, amount) =>
      val replyTo = sender
      Future {buy(future, orderType, price, amount)} onSuccess {case res =>
        replyTo ! ExecutionReport(res)
      } onFailure {case err =>
        replyTo ! ExecutionReport(Left("Adding order failed; Error = "+err.toString))
      }
  }

  private def handleSellCommand: Receive = {
    case Sell(future: FutureContract, orderType, price, amount) =>
      val replyTo = sender
      Future {sell(future, orderType, price, amount)} onSuccess {case res =>
        replyTo ! ExecutionReport(res)
      } onFailure {case err =>
        replyTo ! ExecutionReport(Left("Adding order failed; Error = "+err.toString))
      }
  }
  
  private def handleCancel: Receive = {
    case Cancel(FutOrder(id)) =>
    val replyTo = sender
    Future(cancel(FutOrder(id))) onSuccess {case res =>
      replyTo ! CancelReport(res)
    } onFailure {case err =>
      replyTo ! CancelReport(Left("Canceling order failed; Error = "+err.toString))
    }
  }

  protected def receive = handleBuyCommand orElse handleSellCommand orElse handleCancel orElse whenUnhandled

  private def mapOrderType(orderType: OrderType) = orderType match {
    case OrderType.GoodTillCancelled => 1
    case OrderType.ImmediateOrCancel => 2
    case OrderType.FillOrKill => 3
  }

  private def mapOrderDirection(direction: OrderDirection) = direction match {
    case OrderDirection.Buy => 1
    case OrderDirection.Sell => 2
  }  
  
  private def addOrder(future: FutureContract, orderType: OrderType, direction: OrderDirection, price: BigDecimal, amount: Int): Either[String, FutOrder] = {

    val message = messageFactory.createMessage("FutAddOrder")

    message.destAddr = service.address

    message.setField("P2_Category", FORTS_MSG)
    message.setField("P2_Type", 36)

    message.setField("isin", future.isin.isin)
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

  private def buy(future: FutureContract, orderType: OrderType, price: BigDecimal, amount: Int) = {
    log.debug("Buy: Security = " + future + "; Price = " + price + "; Amount = " + amount + "; Type = " + orderType)
    addOrder(future, orderType, OrderDirection.Buy, price, amount)
  }

  private def sell(future: FutureContract, orderType: OrderType, price: BigDecimal, amount: Int) = {
    log.debug("Sell: Security = " + future + "; Price = " + price + "; Amount = " + amount + "; Type = " + orderType)
    addOrder(future, orderType, OrderDirection.Sell, price, amount)
  }

  private def cancel(order: FutOrder): Either[String, Int] = {
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