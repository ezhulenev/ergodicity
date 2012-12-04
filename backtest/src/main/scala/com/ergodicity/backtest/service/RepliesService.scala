package com.ergodicity.backtest.service

import akka.actor.ActorRef
import com.ergodicity.backtest.cgate.ReplyStreamListenerStubActor.DispatchReply
import com.ergodicity.backtest.cgate.ReplyStreamListenerStubActor.DispatchTimeout
import com.ergodicity.backtest.service.RepliesService.{FailureSerializer, SuccessSerializer}
import com.ergodicity.cgate.scheme.Message
import com.ergodicity.core.Market
import com.ergodicity.core.broker.ReplyEvent.ReplyData
import com.ergodicity.core.broker._

object RepliesService {

  trait SuccessSerializer[M <: Market, R <: Reaction] {
    def success(userId: Int, reaction: R): ReplyData
  }

  trait FailureSerializer[M <: Market, R <: Reaction] {
    def fail(userId: Int, exception: BrokerException): ReplyData = (common(userId) orElse actionFailed(userId)) apply exception

    private def common(userId: Int): PartialFunction[BrokerException, ReplyData] = {
      case FloodException(queueSize, penaltyRemain, message) =>
        val buff = allocate(Size.FORTS_MSG99)
        val msg = new Message.FORTS_MSG99(buff)
        msg.set_queue_size(queueSize)
        msg.set_penalty_remain(penaltyRemain)
        msg.set_message(message)
        ReplyData(userId, Message.FORTS_MSG99.MSG_ID, msg.getData)

      case BrokerErrorException(message) =>
        val buff = allocate(Size.FORTS_MSG100)
        val msg = new Message.FORTS_MSG100(buff)
        msg.set_message(message)
        ReplyData(userId, Message.FORTS_MSG100.MSG_ID, msg.getData)
    }

    def actionFailed(userId: Int): PartialFunction[BrokerException, ReplyData]
  }

  trait ReactionSerializer[M <: Market, R <: Reaction] extends SuccessSerializer[M, R] with FailureSerializer[M, R]

  implicit object FuturesOrderIdSerializer extends ReactionSerializer[Market.Futures, OrderId] {
    def success(userId: Int, reaction: OrderId) = {
      val buff = allocate(Size.FORTS_MSG101)
      val msg = new Message.FORTS_MSG101(buff)
      msg.set_code(0)
      msg.set_order_id(reaction.id)
      ReplyData(userId, Message.FORTS_MSG101.MSG_ID, msg.getData)
    }

    def actionFailed(userId: Int) = {
      case ActionFailedException(code, message) =>
        val buff = allocate(Size.FORTS_MSG101)
        val msg = new Message.FORTS_MSG101(buff)
        msg.set_code(code)
        msg.set_message(message)
        ReplyData(userId, Message.FORTS_MSG101.MSG_ID, msg.getData)
    }
  }

  implicit object OptionsOrderIdSerializer extends ReactionSerializer[Market.Options, OrderId] {
    def success(userId: Int, reaction: OrderId) = {
      val buff = allocate(Size.FORTS_MSG109)
      val msg = new Message.FORTS_MSG109(buff)
      msg.set_code(0)
      msg.set_order_id(reaction.id)
      ReplyData(userId, Message.FORTS_MSG109.MSG_ID, msg.getData)
    }

    def actionFailed(userId: Int) = {
      case ActionFailedException(code, message) =>
        val buff = allocate(Size.FORTS_MSG109)
        val msg = new Message.FORTS_MSG109(buff)
        msg.set_code(code)
        msg.set_message(message)
        ReplyData(userId, Message.FORTS_MSG109.MSG_ID, msg.getData)
    }
  }

  implicit object FuturesCancelledSerializer extends ReactionSerializer[Market.Futures, Cancelled] {
    def success(userId: Int, reaction: Cancelled) = {
      val buff = allocate(Size.FORTS_MSG102)
      val msg = new Message.FORTS_MSG102(buff)
      msg.set_code(0)
      msg.set_amount(reaction.num)
      ReplyData(userId, Message.FORTS_MSG102.MSG_ID, msg.getData)
    }

    def actionFailed(userId: Int) = {
      case ActionFailedException(code, message) =>
        val buff = allocate(Size.FORTS_MSG102)
        val msg = new Message.FORTS_MSG102(buff)
        msg.set_code(code)
        msg.set_message(message)
        ReplyData(userId, Message.FORTS_MSG102.MSG_ID, msg.getData)
    }
  }

  implicit object OptionsCancelledSerializer extends ReactionSerializer[Market.Options, Cancelled] {
    def success(userId: Int, reaction: Cancelled) = {
      val buff = allocate(Size.FORTS_MSG110)
      val msg = new Message.FORTS_MSG110(buff)
      msg.set_code(0)
      msg.set_amount(reaction.num)
      ReplyData(userId, Message.FORTS_MSG110.MSG_ID, msg.getData)
    }

    def actionFailed(userId: Int) = {
      case ActionFailedException(code, message) =>
        val buff = allocate(Size.FORTS_MSG110)
        val msg = new Message.FORTS_MSG110(buff)
        msg.set_code(code)
        msg.set_message(message)
        ReplyData(userId, Message.FORTS_MSG110.MSG_ID, msg.getData)
    }
  }

}

class RepliesService[M <: Market](replies: ActorRef)(implicit context: SessionContext) {
  def reply[R <: Reaction](userId: Int, reaction: R)(implicit serializer: SuccessSerializer[M, R]) {
    replies ! DispatchReply(serializer.success(userId, reaction))
  }

  def fail[R <: Reaction](userId: Int, exception: BrokerException)(implicit serializer: FailureSerializer[M, R]) {
    exception match {
      case BrokerTimedOutException => timeout(userId)
      case _ => replies ! DispatchReply(serializer.fail(userId, exception))
    }
  }

  def timeout(userId: Int) {
    replies ! DispatchTimeout(ReplyEvent.TimeoutMessage(userId))
  }
}