package com.ergodicity.cgate

import akka.actor.{Actor, FSM}
import ru.micexrts.cgate.{CGateException, MessageType}
import java.nio.ByteBuffer
import ru.micexrts.cgate.messages.{StreamDataMessage, Message}


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

  case class StreamData(data: ByteBuffer)

  case object GoOnline extends StreamEvent


  def apply(msg: Message) = msg.getType match {
    case MessageType.MSG_OPEN => Open
    case MessageType.MSG_CLOSE => Close
    case MessageType.MSG_TN_BEGIN => TnBegin
    case MessageType.MSG_TN_COMMIT => TnCommit
    case MessageType.MSG_STREAM_DATA =>
      msg.asInstanceOf[StreamDataMessage].getMsgName
    case MessageType.MSG_P2REPL_ONLINE => GoOnline
    case t => throw new CGateException("Unsupported message type = " + t)
  }
}

class DataStream extends Actor with FSM[DataStreamState, Unit] {

  import StreamEvent._
  import DataStreamState._

  startWith(Closed, ())

  when(Closed) {
    case Event(Open, _) => goto(Opened)
  }

  when(Opened) {
    case Event(GoOnline, _) => goto(Online)
    case Event(Close, _) => goto(Closed)
  }

  when(Online) {
    case Event(Close, _) => goto(Closed)
  }

  initialize
}