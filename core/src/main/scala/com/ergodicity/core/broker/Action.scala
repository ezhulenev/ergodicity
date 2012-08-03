package com.ergodicity.core.broker

import com.ergodicity.core.common.{OrderDirection, OrderType, Isin}
import ru.micexrts.cgate.{Publisher => CGPublisher, MessageKeyType}
import ru.micexrts.cgate.messages.DataMessage
import com.ergodicity.core.broker.Market.{Options, Futures}
import com.ergodicity.core.broker.Command.Command
import com.ergodicity.core.broker.Action.Buy
import com.ergodicity.cgate.scheme.Message

sealed trait Market

object Market {

  sealed trait Futures extends Market

  sealed trait Options extends Market

}

sealed trait OrderFailed

case class Error(message: String) extends OrderFailed

case class Flood(queueSize: Int, penaltyRemain: Int, message: String) extends OrderFailed

case class Order[M <: Market](id: Long)

object Command {

  trait Writes[A <: Action[M], M <: Market] {
    def write(action: A, publisher: CGPublisher): DataMessage
  }

  trait Reads[A <: Action[M], M <: Market] {
    def read(message: DataMessage): Either[OrderFailed, Order[M]]
  }

  trait Command[A <: Action[M], M <: Market] extends Reads[A, M] with Writes[A, M] {
    protected def failures: PartialFunction[(Int, DataMessage), Either[OrderFailed, Order[M]]] = {
      case (Message.FORTS_MSG99.MSG_ID, message) =>
        val msg = new Message.FORTS_MSG99(message.getData)
        Left(Flood(msg.get_queue_size(), msg.get_penalty_remain(), msg.get_message()))

      case (Message.FORTS_MSG100.MSG_ID, message) =>
        val msg = new Message.FORTS_MSG100(message.getData)
        Left(Error(msg.get_message()))
    }
  }

  implicit val FutAddOrder = new Command[Buy[Futures], Futures] {
    val direction = mapOrderDirection(OrderDirection.Buy)

    def write(action: Buy[Futures], publisher: CGPublisher) = {
      val dataMsg = publisher.newMessage(MessageKeyType.KEY_ID, Message.FutAddOrder.MSG_ID).asInstanceOf[DataMessage]
      val command = new Message.FutAddOrder(dataMsg.getData)

      command.set_isin(action.isin.isin)
      command.set_dir(direction)
      command.set_type(mapOrderType(action.orderType))
      command.set_amount(action.amount)
      command.set_price(action.price.toString())

      dataMsg
    }

    def read(message: DataMessage) = (failures orElse order) apply (message.getMsgId, message)

    private def order: PartialFunction[(Int, DataMessage), Either[OrderFailed, Order[Futures]]] = {
      case (Message.FORTS_MSG101.MSG_ID, message) =>
        val msg = new Message.FORTS_MSG101(message.getData)
        Right(Order[Futures](msg.get_order_id()))
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

sealed trait Action[M <: Market]

object Action {

  case class Buy[M <: Market](isin: Isin, amount: Int, price: BigDecimal, orderType: OrderType)(implicit val command: Command[Buy[M], M]) extends Action[M]

  case class Sell[M <: Market](isin: Isin, amount: Int, price: BigDecimal, orderType: OrderType) extends Action[M]

  case class Cancel[M <: Market](order: Order[M]) extends Action[M]

}