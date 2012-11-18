package com.ergodicity.backtest.cgate

import akka.actor._
import akka.dispatch.Await
import akka.pattern.ask
import akka.util
import akka.util.duration._
import com.ergodicity.cgate.{StreamEvent, Active, Closed, State}
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.invocation.InvocationOnMock
import ru.micexrts.cgate.messages._
import ru.micexrts.cgate.{Listener => CGListener, MessageType, CGateException, ISubscriber}
import java.nio.ByteBuffer
import com.ergodicity.backtest.cgate.ListenerStubActor.Command.{GetStateCmd, CloseCmd, OpenCmd}
import scala.Left
import scala.Right

object ListenerStub {

  import ListenerStubActor._

  def wrap(actor: ActorRef) = {
    implicit val timeout = util.Timeout(1.second)

    def execCmd(cmd: Command)(i: InvocationOnMock) {
      Await.result((actor ? cmd).mapTo[Either[Unit, CGateException]], 1.second) fold(s => s, e => throw e)
    }

    def getState(i: InvocationOnMock) = {
      Await.result((actor ? GetStateCmd).mapTo[Int], 1.second)
    }

    val mock = Mockito.mock(classOf[CGListener])
    doAnswer(execCmd(OpenCmd) _).when(mock).open(any())
    doAnswer(execCmd(CloseCmd) _).when(mock).close()
    doAnswer(getState _).when(mock).getState
    mock
  }

  def apply(subscriber: ISubscriber)(implicit context: ActorContext) = wrap(context.actorOf(Props(new ListenerStubActor(subscriber))))

  def apply(name: String, subscriber: ISubscriber)(implicit context: ActorContext) = wrap(context.actorOf(Props(new ListenerStubActor(subscriber)), name))
}

object ListenerStubActor {

  // -- Listener actor commands
  sealed trait Command

  object Command {

    case object OpenCmd extends Command

    case object CloseCmd extends Command

    case object GetStateCmd extends Command

  }

  case class Dispatch(data: Seq[StreamEvent.StreamData])

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
}

class ListenerStubActor(subscriber: ISubscriber, replState: String = "") extends Actor with FSM[State, Seq[StreamEvent.StreamData]] {

  import ListenerStubActor._

  startWith(Closed, Seq.empty)

  when(Closed) {
    case Event(OpenCmd, data) if (data.size == 0) =>
      notify(StreamEvent.Open)
      notify(StreamEvent.StreamOnline)
      goto(Active) replying (Left(()))

    case Event(OpenCmd, data) if (data.size > 0) =>
      notify(StreamEvent.Open)
      notify(StreamEvent.TnBegin)
      data.foreach(notify _)
      notify(StreamEvent.TnCommit)
      notify(StreamEvent.StreamOnline)
      goto(Active) replying (Left(()))

    case Event(CloseCmd, _) => stay() replying (Right(new CGateException("Listener already closed")))

    case Event(Dispatch(data), pending) => stay() using (pending ++ data)
  }

  when(Active) {
    case Event(CloseCmd, _) =>
      notify(StreamEvent.ReplState(replState))
      notify(StreamEvent.Close)
      goto(Closed) replying (Left(()))

    case Event(OpenCmd, _) => stay() replying (Right(new CGateException("Listener already opened")))

    case Event(Dispatch(data), _) =>
      notify(StreamEvent.TnBegin)
      data.foreach(notify _)
      notify(StreamEvent.TnCommit)
      stay()
  }

  whenUnhandled {
    case Event(GetStateCmd, _) => stay() replying (stateName.value)
  }

  private def notify(event: StreamEvent) {
    subscriber onMessage(null, null, event)
  }
}