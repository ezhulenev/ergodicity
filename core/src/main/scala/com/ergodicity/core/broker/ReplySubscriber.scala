package com.ergodicity.core.broker

import java.nio.ByteBuffer
import ru.micexrts.cgate.messages.{P2MqTimeOutMessage, DataMessage, Message}
import akka.actor.ActorRef
import com.ergodicity.cgate.Subscriber
import ru.micexrts.cgate.{ErrorCode, MessageType}

sealed trait ReplyEvent

object ReplyEvent {

  case class ReplyData(id: Int, messageId: Int, data: ByteBuffer) extends ReplyEvent

  case class TimeoutMessage(id: Int) extends ReplyEvent

  case class UnsupportedMessage(msg: Message) extends ReplyEvent

}

class ReplySubscriber(dataStream: ActorRef) extends Subscriber {

  import ReplyEvent._

  private def decode(msg: Message) = msg.getType match {
    case MessageType.MSG_DATA =>
      val dataMsg = msg.asInstanceOf[DataMessage]

      ReplyData(dataMsg.getUserId, dataMsg.getMsgId, dataMsg.getData)

    case MessageType.MSG_P2MQ_TIMEOUT =>
      val timeoutMsg = msg.asInstanceOf[P2MqTimeOutMessage]
      TimeoutMessage(timeoutMsg.getUserId)

    case _ => UnsupportedMessage(msg)
  }

  def handleMessage(msg: Message) = {
    dataStream ! decode(msg)
    ErrorCode.OK
  }
}