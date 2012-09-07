package com.ergodicity.core.broker

import com.ergodicity.core._
import broker.Protocol.Protocol
import ru.micexrts.cgate.{Publisher => CGPublisher, MessageKeyType}
import ru.micexrts.cgate.messages.DataMessage
import com.ergodicity.core.broker.Action.{Cancel, AddOrder}
import com.ergodicity.cgate.scheme.Message
import com.ergodicity.core.Market.{Options, Futures}
import java.nio.ByteBuffer

private[broker] trait MarketCommand[A <: Action[R], R <: Reaction, M <: Market] {
  def encode(publisher: CGPublisher)(implicit config: Broker.Config): DataMessage

  def decode(msgId: Int, data: ByteBuffer): R
}

private[broker] object MarketCommand {
  def apply[A <: Action[R], R <: Reaction, M <: Market](action: A)(implicit protocol: Protocol[A, R, M]) = new MarketCommand[A, R, M] {
    def encode(publisher: CGPublisher)(implicit config: Broker.Config) = protocol.serialize(action, publisher)

    def decode(msgId: Int, data: ByteBuffer) = protocol.deserialize(msgId, data)

    override def toString = action.toString
  }
}


private[broker] sealed trait Action[R <: Reaction]

private[broker] object Action {

  case class AddOrder(isin: Isin, amount: Int, price: BigDecimal, orderType: OrderType, direction: OrderDirection) extends Action[OrderId]

  case class Cancel(order: OrderId) extends Action[Cancelled]
}


sealed abstract class BrokerException(message: String) extends RuntimeException(message)

case class BrokerTimedOutException() extends BrokerException("Broker timed out")

case class BrokerErrorException(message: String) extends BrokerException(message)

case class FloodException(queueSize: Int, penaltyRemain: Int, message: String) extends BrokerException("Flood error; Queue size = "+queueSize+"; penaltyRemain = " + penaltyRemain+", message = "+message)

case class ActionFailedException(code: Int, message: String) extends BrokerException("Action failed; code = "+code+"; message = "+message)


sealed trait Reaction

case class OrderId(id: Long) extends Reaction

case class Cancelled(num: Int) extends Reaction


object Protocol {

  trait Serialize[A <: Action[R], R <: Reaction] {
    def serialize(action: A, publisher: CGPublisher)(implicit config: Broker.Config): DataMessage
  }

  trait Deserialize[A <: Action[R], R <: Reaction] {
    def deserialize(msgId: Int, data: ByteBuffer): R
  }

  trait Protocol[A <: Action[R], R <: Reaction, M <: Market] extends Serialize[A, R] with Deserialize[A, R] {
    def deserialize(msgId: Int, data: ByteBuffer) = (failures orElse payload) apply msgId -> data

    def payload: PartialFunction[(Int, ByteBuffer), R]

    protected def failures: PartialFunction[(Int, ByteBuffer), R] = {
      case (Message.FORTS_MSG99.MSG_ID, data) =>
        val floodErr = new Message.FORTS_MSG99(data)
        throw new FloodException(floodErr.get_queue_size(), floodErr.get_penalty_remain(), floodErr.get_message())

      case (Message.FORTS_MSG100.MSG_ID, data) =>
        val error = new Message.FORTS_MSG100(data)
        throw new BrokerErrorException(error.get_message())
    }
  }

  implicit val FutAddOrder = new Protocol[AddOrder, OrderId, Futures] {
    def serialize(action: AddOrder, publisher: CGPublisher)(implicit config: Broker.Config) = {
      val dataMsg = publisher.newMessage(MessageKeyType.KEY_ID, Message.FutAddOrder.MSG_ID).asInstanceOf[DataMessage]
      val command = new Message.FutAddOrder(dataMsg.getData)

      command.set_isin(action.isin.isin)
      command.set_dir(mapOrderDirection(action.direction))
      command.set_type(mapOrderType(action.orderType))
      command.set_amount(action.amount)
      command.set_price(action.price.toString())
      command.set_client_code(config.clientCode)

      dataMsg
    }

    def payload: PartialFunction[(Int, ByteBuffer), OrderId] = {
      case (Message.FORTS_MSG101.MSG_ID, data) =>
        val msg = new Message.FORTS_MSG101(data)
        if (msg.get_code() == 0)
          OrderId(msg.get_order_id())
        else
          throw ActionFailedException(msg.get_code(), msg.get_message())
    }
  }

  implicit val OptAddOrder = new Protocol[AddOrder, OrderId, Options] {
    def serialize(action: AddOrder, publisher: CGPublisher)(implicit config: Broker.Config) = {
      val dataMsg = publisher.newMessage(MessageKeyType.KEY_ID, Message.OptAddOrder.MSG_ID).asInstanceOf[DataMessage]
      val command = new Message.OptAddOrder(dataMsg.getData)

      command.set_isin(action.isin.isin)
      command.set_dir(mapOrderDirection(action.direction))
      command.set_type(mapOrderType(action.orderType))
      command.set_amount(action.amount)
      command.set_price(action.price.toString())
      command.set_client_code(config.clientCode)

      dataMsg
    }

    def payload: PartialFunction[(Int, ByteBuffer), OrderId] = {
      case (Message.FORTS_MSG109.MSG_ID, data) =>
        val msg = new Message.FORTS_MSG109(data)
        if (msg.get_code() == 0)
          OrderId(msg.get_order_id())
        else
          throw ActionFailedException(msg.get_code(), msg.get_message())
    }
  }

  implicit val FutDelOrder = new Protocol[Cancel, Cancelled, Futures] {
    def serialize(action: Cancel, publisher: CGPublisher)(implicit config: Broker.Config) = {
      val dataMsg = publisher.newMessage(MessageKeyType.KEY_ID, Message.FutDelOrder.MSG_ID).asInstanceOf[DataMessage]
      val command = new Message.FutDelOrder(dataMsg.getData)
      command.set_order_id(action.order.id)
      dataMsg
    }

    def payload: PartialFunction[(Int, ByteBuffer), Cancelled] = {
      case (Message.FORTS_MSG102.MSG_ID, data) =>
        val msg = new Message.FORTS_MSG102(data)
        if (msg.get_code() == 0)
          Cancelled(msg.get_amount())
        else
          throw new ActionFailedException(msg.get_code(), msg.get_message())
    }
  }

  implicit val OptDelOrder = new Protocol[Cancel, Cancelled, Options] {
    def serialize(action: Cancel, publisher: CGPublisher)(implicit config: Broker.Config) = {
      val dataMsg = publisher.newMessage(MessageKeyType.KEY_ID, Message.OptDelOrder.MSG_ID).asInstanceOf[DataMessage]
      val command = new Message.OptDelOrder(dataMsg.getData)
      command.set_order_id(action.order.id)
      dataMsg
    }

    def payload: PartialFunction[(Int, ByteBuffer), Cancelled] = {
      case (Message.FORTS_MSG110.MSG_ID, data) =>
        val msg = new Message.FORTS_MSG110(data)
        if (msg.get_code() == 0)
          Cancelled(msg.get_amount())
        else
          throw new ActionFailedException(msg.get_code(), msg.get_message())
    }
  }


  def mapOrderType(orderType: OrderType) = orderType match {
    case OrderType.GoodTillCancelled => 1
    case OrderType.ImmediateOrCancel => 2
    case OrderType.FillOrKill => 3
  }

  def mapOrderDirection(direction: OrderDirection) = direction match {
    case OrderDirection.Buy => 1
    case OrderDirection.Sell => 2
  }
}

