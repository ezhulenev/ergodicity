package com.ergodicity.cgate

import akka.actor.{LoggingFSM, ActorRef, Actor, FSM}
import ru.micexrts.cgate.{ErrorCode, MessageType}
import ru.micexrts.cgate.messages._
import java.nio.ByteBuffer
import com.ergodicity.cgate.DataStream.StreamEventSubscribers
import com.ergodicity.cgate.StreamEvent.ReplState


sealed trait DataStreamState

object DataStreamState {

  case object Closed extends DataStreamState

  case object Opened extends DataStreamState

  case object Online extends DataStreamState

}

sealed trait StreamEvent

object StreamEvent {

  case object Open extends StreamEvent

  case object Close extends StreamEvent

  case object TnBegin extends StreamEvent

  case object TnCommit extends StreamEvent

  case object StreamOnline extends StreamEvent

  case class StreamData(tableIndex: Int, data: ByteBuffer) extends StreamEvent

  case class LifeNumChanged(lifeNum: Long) extends StreamEvent

  case class ClearDeleted(tableIndex: Int, rev: Long) extends StreamEvent

  case class ReplState(state: String) extends StreamEvent

  case class UnsupportedMessage(msg: Message) extends StreamEvent

}

class DataStreamSubscriber(dataStream: ActorRef) extends Subscriber {

  import StreamEvent._

  private def decode(msg: Message) = msg.getType match {
    case MessageType.MSG_OPEN => Open

    case MessageType.MSG_CLOSE => Close

    case MessageType.MSG_TN_BEGIN => TnBegin

    case MessageType.MSG_TN_COMMIT => TnCommit

    case MessageType.MSG_P2REPL_ONLINE => StreamOnline

    case MessageType.MSG_STREAM_DATA =>
      val dataMsg = msg.asInstanceOf[StreamDataMessage]
      StreamData(dataMsg.getMsgIndex, clone(dataMsg.getData))

    case MessageType.MSG_P2REPL_LIFENUM =>
      val lifeNumMsg = msg.asInstanceOf[P2ReplLifeNumMessage]
      LifeNumChanged(lifeNumMsg.getLifenum)

    case MessageType.MSG_P2REPL_CLEARDELETED =>
      val clearMsg = msg.asInstanceOf[P2ReplClearDeletedMessage]
      ClearDeleted(clearMsg.getTableIdx, clearMsg.getTableRev)

    case MessageType.MSG_P2REPL_REPLSTATE =>
      val replStateMsg = msg.asInstanceOf[P2ReplStateMessage]
      ReplState(replStateMsg.getReplState)

    case _ => UnsupportedMessage(msg)
  }

  def handleMessage(msg: Message) = {
    dataStream ! decode(msg)
    ErrorCode.OK
  }
}

object DataStream {

  class UnsupportedMessageException(message: String) extends RuntimeException(message)

  case class SubscribeCloseEvent(ref: ActorRef)

  case class DataStreamClosed(stream: ActorRef, state: ReplState)

  case class SubscribeStreamEvents(ref: ActorRef)

  case class StreamEventSubscribers(set: Set[ActorRef] = Set()) {
    def apply(msg: Any) {
      set foreach (_ ! msg)
    }

    def subscribe(ref: ActorRef) = StreamEventSubscribers(set + ref)
  }

}

class DataStream extends Actor with LoggingFSM[DataStreamState, StreamEventSubscribers] {

  import DataStream._
  import StreamEvent._
  import DataStreamState._

  var onClosedSubscribers: Set[ActorRef] = Set()

  startWith(Closed, StreamEventSubscribers())

  when(Closed) {
    case Event(Open, _) => goto(Opened)
  }

  when(Opened)(handleStreamEvents orElse {
    case Event(StreamOnline, subscribers) => goto(Online)

    case Event(Close, _) => goto(Closed)
  })

  when(Online)(handleStreamEvents orElse {
    case Event(Close, _) => goto(Closed)
  })

  whenUnhandled {
    case Event(SubscribeStreamEvents(ref), subscribers) =>
      stay() using subscribers.subscribe(ref)

    case Event(SubscribeCloseEvent(ref), _) =>
      onClosedSubscribers = onClosedSubscribers + ref
      stay()

    case Event(e@ReplState(state), subscribers) =>
      onClosedSubscribers foreach (_ ! DataStreamClosed(self, e))
      stay()

    case Event(err@UnsupportedMessage(msg), _) =>
      throw new UnsupportedMessageException("Unsupported message = " + msg)
  }

  private def handleStreamEvents: StateFunction = {
    case Event(e@(TnBegin | TnCommit), subscribers) =>
      subscribers(e)
      stay()

    case Event(e@StreamData(idx, _), subscribers) =>
      subscribers(e)
      stay()

    case Event(e@ClearDeleted(idx, _), subscribers) =>
      subscribers(e)
      stay()

    case Event(e@LifeNumChanged(_), subscribers) =>
      subscribers(e)
      stay()
  }

  initialize
}