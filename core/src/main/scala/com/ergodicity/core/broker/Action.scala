package com.ergodicity.core.broker

import com.ergodicity.core.{Market, OrderDirection, OrderType, Isin}
import ru.micexrts.cgate.{Publisher => CGPublisher, MessageKeyType}
import ru.micexrts.cgate.messages.DataMessage
import com.ergodicity.core.Market.{Options, Futures}
import com.ergodicity.core.broker.Protocol.Protocol
import com.ergodicity.core.broker.Action.AddOrder
import com.ergodicity.cgate.scheme.Message

private[broker] sealed trait Action[R <: Reaction]

private[broker] object Action {

  case class AddOrder(isin: Isin, amount: Int, price: BigDecimal, orderType: OrderType, direction: OrderDirection)
                     (implicit val p: Protocol[AddOrder, _ <: Market, Order]) extends Action[Order] {
    def protocol = p
  }

}


sealed trait ActionFailed

case class Error(message: String) extends ActionFailed

case class Flood(queueSize: Int, penaltyRemain: Int, message: String) extends ActionFailed


sealed trait Reaction

case class Order(id: Long) extends Reaction

case class Cancelled(num: Int) extends Reaction


object Protocol {

  trait Serialize[A <: Action[_], M <: Market] {
    def serialize(action: A, publisher: CGPublisher): DataMessage
  }

  trait Deserialize[A <: Action[_], M <: Market, R <: Reaction] {
    def deserialize(message: DataMessage): Either[ActionFailed, R]
  }

  trait Protocol[A <: Action[R], M <: Market, R <: Reaction] extends Serialize[A, M] with Deserialize[A, M, R] {
    protected def failures: PartialFunction[(Int, DataMessage), Either[ActionFailed, R]] = {
      case (Message.FORTS_MSG99.MSG_ID, message) =>
        val msg = new Message.FORTS_MSG99(message.getData)
        Left(Flood(msg.get_queue_size(), msg.get_penalty_remain(), msg.get_message()))

      case (Message.FORTS_MSG100.MSG_ID, message) =>
        val msg = new Message.FORTS_MSG100(message.getData)
        Left(Error(msg.get_message()))
    }
  }

  implicit val FutAddOrder = new Protocol[AddOrder, Futures, Order] {
    def serialize(action: AddOrder, publisher: CGPublisher) = {
      val dataMsg = publisher.newMessage(MessageKeyType.KEY_ID, Message.FutAddOrder.MSG_ID).asInstanceOf[DataMessage]
      val command = new Message.FutAddOrder(dataMsg.getData)

      command.set_isin(action.isin.isin)
      command.set_dir(mapOrderDirection(action.direction))
      command.set_type(mapOrderType(action.orderType))
      command.set_amount(action.amount)
      command.set_price(action.price.toString())

      dataMsg
    }

    def deserialize(message: DataMessage) = (failures orElse futOrder) apply(message.getMsgId, message)

    private def futOrder: PartialFunction[(Int, DataMessage), Either[ActionFailed, Order]] = {
      case (Message.FORTS_MSG101.MSG_ID, message) =>
        val msg = new Message.FORTS_MSG101(message.getData)
        Right(Order(msg.get_order_id()))
    }
  }

  implicit val OptAddOrder = new Protocol[AddOrder, Options, Order] {
    def serialize(action: AddOrder, publisher: CGPublisher) = {
      val dataMsg = publisher.newMessage(MessageKeyType.KEY_ID, Message.OptAddOrder.MSG_ID).asInstanceOf[DataMessage]
      val command = new Message.OptAddOrder(dataMsg.getData)

      command.set_isin(action.isin.isin)
      command.set_dir(mapOrderDirection(action.direction))
      command.set_type(mapOrderType(action.orderType))
      command.set_amount(action.amount)
      command.set_price(action.price.toString())

      dataMsg
    }

    def deserialize(message: DataMessage) = (failures orElse optOrder) apply(message.getMsgId, message)

    private def optOrder: PartialFunction[(Int, DataMessage), Either[ActionFailed, Order]] = {
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

