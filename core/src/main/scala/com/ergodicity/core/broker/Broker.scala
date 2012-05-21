package com.ergodicity.core.broker

import akka.actor.Actor
import com.ergodicity.plaza2.Connection
import com.ergodicity.core.common.{FutureContract, Security}
import akka.event.Logging
import plaza2.{Message, MessageFactory, Connection => P2Connection}
import akka.dispatch.{ExecutionContext, Future}

object OrderType {
  def apply(orderType: OrderType) = orderType match {
    case GoodTillCancelled => 1
    case ImmediateOrCancel => 2
    case FillOrKill => 3
  }
}

sealed trait OrderType

case object GoodTillCancelled extends OrderType

case object ImmediateOrCancel extends OrderType

case object FillOrKill extends OrderType

object OrderDirection {
  def apply(direction: OrderDirection) = direction match {
    case Buy => 1
    case Sell => 2
  }
}

sealed trait OrderDirection

case object Buy extends OrderDirection

case object Sell extends OrderDirection


// Commands
case class AddOrder(security: Security, orderType: OrderType, direction: OrderDirection, price: BigDecimal, amount: Int)

sealed trait AddOrderResponse
case class OrderId(id: Long) extends AddOrderResponse
case class OrderFailed(message: String) extends AddOrderResponse

object Broker {
  def apply(clientCode: String, connection: P2Connection)(implicit messageFactory: MessageFactory) = new Broker(clientCode, connection)
}

class Broker(clientCode: String, connection: P2Connection)(implicit messageFactory: MessageFactory) extends Actor {
  private val log = Logging(context.system, self)
  
  implicit val ec = ExecutionContext.defaultExecutionContext(context.system)

  private def handleFutAddOrder: Receive = {
    case AddOrder(FutureContract(isin, _, _, _), orderType, direction, price, amount) =>
      log.debug("Add order; Security = " + isin + "; Type = " + orderType + "; Direction = " + direction + "; Price = " + price + " Amount = " + amount)

      val message = messageFactory.createMessage("FutAddOrder")

      Future {
        message.send(connection)
      } onSuccess {
        case response: Message =>
          if (response.field("code").getInt == 0) {
            sender ! OrderId(response.field("code").getLong)
          } else {
            sender ! OrderFailed(response.field("message").getString)
          }
      }
  }

  private def unhandled: Receive = {
    case e => log.warning("Unhandled event: " + e)
  }

  protected def receive = handleFutAddOrder orElse unhandled
}