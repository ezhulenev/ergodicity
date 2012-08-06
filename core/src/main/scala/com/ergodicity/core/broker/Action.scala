package com.ergodicity.core.broker

import com.ergodicity.core._
import broker.Protocol.Protocol
import ru.micexrts.cgate.{Publisher => CGPublisher, MessageKeyType}
import ru.micexrts.cgate.messages.DataMessage
import com.ergodicity.core.broker.Action.{Cancel, AddOrder}
import com.ergodicity.cgate.scheme.Message
import scala.{Either, Left, Right}
import com.ergodicity.core.Market.{Options, Futures}

private[broker] trait MarketCommand[A <: Action[R], R <: Reaction, M <: Market] {
  def encode(publisher: CGPublisher): DataMessage

  def decode(message: DataMessage): Either[ActionFailed, R]
}

private[broker] object MarketCommand {
  def apply[A <: Action[R], R <: Reaction, M <: Market](action: A)(implicit protocol: Protocol[A, R, M]) = new MarketCommand[A, R, M] {
    def encode(publisher: CGPublisher) = protocol.serialize(action, publisher)

    def decode(message: DataMessage) = protocol.deserialize(message)
  }
}


private[broker] sealed trait Action[R <: Reaction]

private[broker] object Action {

  case class AddOrder(isin: Isin, amount: Int, price: BigDecimal, orderType: OrderType, direction: OrderDirection) extends Action[Order]

  case class Cancel(order: Order) extends Action[Cancelled]

}


sealed trait ActionFailed

case class Error(message: String) extends ActionFailed

case class Flood(queueSize: Int, penaltyRemain: Int, message: String) extends ActionFailed


sealed trait Reaction

case class Order(id: Long) extends Reaction

case class Cancelled(num: Int) extends Reaction


object Protocol {

  trait Serialize[A <: Action[R], R <: Reaction] {
    def serialize(action: A, publisher: CGPublisher): DataMessage
  }

  trait Deserialize[A <: Action[R], R <: Reaction] {
    def deserialize(message: DataMessage): Either[ActionFailed, R]
  }

  trait Protocol[A <: Action[R], R <: Reaction, M <: Market] extends Serialize[A, R] with Deserialize[A, R] {
    def deserialize(message: DataMessage) = (failures orElse payload) apply (message)

    def payload: PartialFunction[DataMessage, Either[ActionFailed, R]]

    protected def failures: PartialFunction[DataMessage, Either[ActionFailed, R]] = {
      case message if (message.getMsgId == Message.FORTS_MSG99.MSG_ID) =>
        val floodErr = new Message.FORTS_MSG99(message.getData)
        Left(Flood(floodErr.get_queue_size(), floodErr.get_penalty_remain(), floodErr.get_message()))

      case message if (message.getMsgId == Message.FORTS_MSG100.MSG_ID) =>
        val error = new Message.FORTS_MSG100(message.getData)
        Left(Error(error.get_message()))
    }
  }

  implicit val FutAddOrder = new Protocol[AddOrder, Order, Futures] {
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

    def payload = {
      case message if (message.getMsgId == Message.FORTS_MSG101.MSG_ID) =>
        val msg = new Message.FORTS_MSG101(message.getData)
        Right(Order(msg.get_order_id()))
    }
  }

  implicit val OptAddOrder = new Protocol[AddOrder, Order, Options] {
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

    def payload = {
      case message if (message.getMsgId == Message.FORTS_MSG109.MSG_ID) =>
        val msg = new Message.FORTS_MSG109(message.getData)
        Right(Order(msg.get_order_id()))
    }
  }

  implicit val FutDelOrder = new Protocol[Cancel, Cancelled, Futures] {
    def serialize(action: Cancel, publisher: CGPublisher) = {
      val dataMsg = publisher.newMessage(MessageKeyType.KEY_ID, Message.FutDelOrder.MSG_ID).asInstanceOf[DataMessage]
      val command = new Message.FutDelOrder(dataMsg.getData)

      command.set_order_id(action.order.id)

      dataMsg
    }

    def payload = {
      case message if (message.getMsgId == Message.FORTS_MSG102.MSG_ID) =>
        val msg = new Message.FORTS_MSG102(message.getData)
        Right(Cancelled(msg.get_amount()))
    }
  }

  implicit val OptDelOrder = new Protocol[Cancel, Cancelled, Options] {
    def serialize(action: Cancel, publisher: CGPublisher) = {
      val dataMsg = publisher.newMessage(MessageKeyType.KEY_ID, Message.OptDelOrder.MSG_ID).asInstanceOf[DataMessage]
      val command = new Message.OptDelOrder(dataMsg.getData)
      command.set_order_id(action.order.id)
      dataMsg
    }

    def payload = {
      case message if (message.getMsgId == Message.FORTS_MSG110.MSG_ID) =>
        val msg = new Message.FORTS_MSG110(message.getData)
        Right(Cancelled(msg.get_amount()))
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

