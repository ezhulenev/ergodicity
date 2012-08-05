package com.ergodicity.core.broker

import com.ergodicity.core.common.{OrderDirection, OrderType, Isin}
import ru.micexrts.cgate.{Publisher => CGPublisher, MessageKeyType}
import ru.micexrts.cgate.messages.DataMessage
import com.ergodicity.core.broker.Market.{Options, Futures}
import com.ergodicity.core.broker.Protocol.Protocol
import com.ergodicity.core.broker.Action.AddOrder
import com.ergodicity.cgate.scheme.Message

sealed trait Market

object Market {

  sealed trait Futures extends Market

  sealed trait Options extends Market

}

private[broker] sealed trait Action[M <: Market]

private[broker] object Action {

  case class AddOrder[M <: Market](isin: Isin, amount: Int, price: BigDecimal, orderType: OrderType, direction: OrderDirection)(implicit val command: Protocol[AddOrder[M], M, Order]) extends Action[M]

  case class DelOrder[M <: Market](order: Order) extends Action[M]
}


sealed trait ActionFailed

case class Error(message: String) extends ActionFailed

case class Flood(queueSize: Int, penaltyRemain: Int, message: String) extends ActionFailed


sealed trait Reaction

case class Order(id: Long) extends Reaction

case class Cancelled(num: Int) extends Reaction


object Protocol {

  trait Send[A <: Action[M], M <: Market] {
    def send(action: A, publisher: CGPublisher): DataMessage
  }

  trait Receive[A <: Action[M], M <: Market, R <: Reaction] {
    def receive(message: DataMessage): Either[ActionFailed, Reaction]
  }

  trait Protocol[A <: Action[M], M <: Market, R <: Reaction] extends Send[A, M] with Receive[A, M, R] {
    protected def failures: PartialFunction[(Int, DataMessage), Either[ActionFailed, Reaction]] = {
      case (Message.FORTS_MSG99.MSG_ID, message) =>
        val msg = new Message.FORTS_MSG99(message.getData)
        Left(Flood(msg.get_queue_size(), msg.get_penalty_remain(), msg.get_message()))

      case (Message.FORTS_MSG100.MSG_ID, message) =>
        val msg = new Message.FORTS_MSG100(message.getData)
        Left(Error(msg.get_message()))
    }
  }

  implicit val FutAddOrder = new Protocol[AddOrder[Futures], Futures, Order] {
    def send(action: AddOrder[Futures], publisher: CGPublisher) = {
      val dataMsg = publisher.newMessage(MessageKeyType.KEY_ID, Message.FutAddOrder.MSG_ID).asInstanceOf[DataMessage]
      val command = new Message.FutAddOrder(dataMsg.getData)

      command.set_isin(action.isin.isin)
      command.set_dir(mapOrderDirection(action.direction))
      command.set_type(mapOrderType(action.orderType))
      command.set_amount(action.amount)
      command.set_price(action.price.toString())

      dataMsg
    }

    def receive(message: DataMessage) = (failures orElse futOrder) apply(message.getMsgId, message)

    private def futOrder: PartialFunction[(Int, DataMessage), Either[ActionFailed, Reaction]] = {
      case (Message.FORTS_MSG101.MSG_ID, message) =>
        val msg = new Message.FORTS_MSG101(message.getData)
        Right(Order(msg.get_order_id()))
    }
  }

  implicit val OptAddOrder = new Protocol[AddOrder[Options], Options, Order] {
    def send(action: AddOrder[Options], publisher: CGPublisher) = {
      val dataMsg = publisher.newMessage(MessageKeyType.KEY_ID, Message.OptAddOrder.MSG_ID).asInstanceOf[DataMessage]
      val command = new Message.OptAddOrder(dataMsg.getData)

      command.set_isin(action.isin.isin)
      command.set_dir(mapOrderDirection(action.direction))
      command.set_type(mapOrderType(action.orderType))
      command.set_amount(action.amount)
      command.set_price(action.price.toString())

      dataMsg
    }

    def receive(message: DataMessage) = (failures orElse optOrder) apply(message.getMsgId, message)

    private def optOrder: PartialFunction[(Int, DataMessage), Either[ActionFailed, Reaction]] = {
      case (Message.FORTS_MSG109.MSG_ID, message) =>
        val msg = new Message.FORTS_MSG109(message.getData)
        Right(Order(msg.get_order_id()))
    }
  }

  private def mapOrderType(orderType: OrderType) = orderType match {
    case OrderType.GoodTillCancelled => 1
    case OrderType.ImmediateOrCancel => 2
    case OrderType.FillOrKill => 3
  }

  private def mapOrderDirection(direction: OrderDirection) = direction match {
    case OrderDirection.Buy => 1
    case OrderDirection.Sell => 2
  }

}

