package com.ergodicity.backtest.cgate

import akka.actor.{ActorRef, FSM, Actor}
import akka.dispatch.Await
import akka.pattern.ask
import akka.util
import akka.util.duration._
import com.ergodicity.cgate.{StreamEvent, Active, Closed, State}
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import ru.micexrts.cgate.messages._
import ru.micexrts.cgate.{Listener => CGListener, MessageType, CGateException, ISubscriber}
import java.nio.ByteBuffer
import com.ergodicity.backtest.cgate.Listener.Command.OpenCmd
import scala.Left
import scala.Right

object Listener {

  // -- Listener actor commands
  sealed trait Command

  object Command {

    case object OpenCmd extends Command

  }

  // -- Convert StreamEvent into CGate Message
  implicit def toCGateMessage(event: StreamEvent): Message = {
    def typeOf(t: Int) = {
      val msg = Mockito.mock(classOf[Message])
      Mockito.when(msg.getType).thenReturn(t)
      msg
    }

    def dataMsg(tableIndex: Int, data: ByteBuffer) = {
      val message = Mockito.mock(classOf[StreamDataMessage])
      Mockito.when(message.getType).thenReturn(MessageType.MSG_STREAM_DATA)
      Mockito.when(message.getMsgIndex).thenReturn(tableIndex)
      Mockito.when(message.getData).thenReturn(data)
      message
    }

    def lifeNumMsg(lifeNum: Long) = {
      val message = Mockito.mock(classOf[P2ReplLifeNumMessage])
      Mockito.when(message.getType).thenReturn(MessageType.MSG_P2REPL_LIFENUM)
      Mockito.when(message.getLifenum).thenReturn(lifeNum)
      message
    }

    def clearDeletedMsg(tableIndex: Int, tableRev: Long) = {
      val message = Mockito.mock(classOf[P2ReplClearDeletedMessage])
      Mockito.when(message.getType).thenReturn(MessageType.MSG_P2REPL_CLEARDELETED)
      Mockito.when(message.getTableIdx).thenReturn(tableIndex)
      Mockito.when(message.getTableRev).thenReturn(tableRev)
      message
    }

    def replStateMsg(state: String) = {
      val message = Mockito.mock(classOf[P2ReplStateMessage])
      Mockito.when(message.getType).thenReturn(MessageType.MSG_P2REPL_REPLSTATE)
      Mockito.when(message.getReplState).thenReturn(state)
      message
    }

    import StreamEvent._
    event match {
      case Open => typeOf(MessageType.MSG_OPEN)
      case Close => typeOf(MessageType.MSG_CLOSE)
      case TnBegin => typeOf(MessageType.MSG_TN_BEGIN)
      case TnCommit => typeOf(MessageType.MSG_TN_COMMIT)
      case StreamOnline => typeOf(MessageType.MSG_P2REPL_ONLINE)
      case StreamData(tableIndex, data) => dataMsg(tableIndex, data)
      case LifeNumChanged(lifeNum) => lifeNumMsg(lifeNum)
      case ClearDeleted(tableIndex, tableRev) => clearDeletedMsg(tableIndex, tableRev)
      case ReplState(state) => replStateMsg(state)
      case msg@UnsupportedMessage(_) => throw new IllegalArgumentException("Unsupported msg = " + msg)
    }
  }

  implicit def toAnswer[A](f: InvocationOnMock => A) = new Answer[A] {
    def answer(invocation: InvocationOnMock) = f(invocation)
  }

  // -- Create wrapper over backtest Listener actor
  def apply(actor: ActorRef) = {
    implicit val timeout = util.Timeout(1.second)

    def open(i: InvocationOnMock) {
      Await.result((actor ? OpenCmd).mapTo[Either[Unit, CGateException]], 1.second) fold(_ => (), e => throw e)
    }

    val mock = Mockito.mock(classOf[CGListener])
    doAnswer(open _).when(mock).open(any())
    mock
  }
}


class ListenerActor(subscriber: ISubscriber) extends Actor with FSM[State, Unit] {
  import Listener._

  startWith(Closed, ())

  when(Closed) {
    case Event(OpenCmd, _) =>
      notify(StreamEvent.Open)
      goto(Active) replying (Left(()))
  }

  when(Active) {
    case Event(OpenCmd, _) => stay() replying (Right(new CGateException("Listener already opened")))
  }

  private def notify(event: StreamEvent) {
    subscriber onMessage (null, null, event)
  }
}