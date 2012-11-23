package com.ergodicity.backtest.cgate

import akka.actor._
import akka.dispatch.Await
import akka.pattern.ask
import akka.util
import akka.util.duration._
import com.ergodicity.backtest.cgate.ListenerStubActor.Command.Bind
import com.ergodicity.backtest.cgate.ListenerStubActor.Command.{GetStateCmd, CloseCmd, OpenCmd}
import com.ergodicity.backtest.cgate.ListenerStubState.Binded
import com.ergodicity.backtest.cgate.ListenerStubState.UnBinded
import com.ergodicity.cgate._
import java.nio.ByteBuffer
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.invocation.InvocationOnMock
import ru.micexrts.cgate.messages._
import ru.micexrts.cgate.{Listener => CGListener, MessageType, CGateException, ISubscriber}
import scala.Left
import scala.Right
import scala.Some

object ListenerDecoratorStub {

  import ListenerStubActor._

  def wrap(actor: ActorRef) = {
    implicit val timeout = util.Timeout(1.second)

    def execCmd(cmd: Command)(i: InvocationOnMock) {
      Await.result((actor ? cmd).mapTo[Either[Unit, CGateException]], 1.second) fold(s => s, e => throw e)
    }

    def getState(i: InvocationOnMock) = {
      Await.result((actor ? GetStateCmd).mapTo[State], 1.second).value
    }

    val mock = Mockito.mock(classOf[CGListener])
    doAnswer(execCmd(OpenCmd) _).when(mock).open(any())
    doAnswer(execCmd(CloseCmd) _).when(mock).close()
    doAnswer(getState _).when(mock).getState
    mock

    new ListenerDecorator(subscriber => {
      actor ! Bind(subscriber)
      mock
    })
  }

  def apply()(implicit context: ActorContext) = wrap(context.actorOf(Props(new ListenerStubActor())))

  def apply(name: String)(implicit context: ActorContext) = wrap(context.actorOf(Props(new ListenerStubActor()), name))
}


object ListenerStubActor {

  // -- Listener actor commands
  sealed trait Command

  object Command {

    case object OpenCmd extends Command

    case object CloseCmd extends Command

    case object GetStateCmd extends Command

    case class Bind(subscriber: ISubscriber) extends Command

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

sealed trait ListenerStubState

object ListenerStubState {

  case object UnBinded extends ListenerStubState

  case class Binded(state: State) extends ListenerStubState

}

class ListenerStubActor(replState: String = "") extends Actor with FSM[ListenerStubState, Seq[StreamEvent.StreamData]] {

  import ListenerStubActor._

  private[this] var subscriber: Option[ISubscriber] = None

  startWith(UnBinded, Seq.empty)

  when(UnBinded, stateTimeout = 100.millis) {
    case Event(Bind(s), _) =>
      subscriber = Some(s)
      goto(Binded(Closed))

    case Event(Dispatch(data), pending) => stay() using (pending ++ data)

    case Event(FSM.StateTimeout, _) => throw new IllegalActorStateException("Timed out in Binding state")

    case e => throw new IllegalActorStateException("Actor is UnBinded; Event = " + e)
  }

  when(Binded(Closed)) {
    case Event(OpenCmd, data) if (data.size == 0) =>
      notify(StreamEvent.Open)
      notify(StreamEvent.StreamOnline)
      goto(Binded(Active)) replying (Left(()))

    case Event(OpenCmd, data) if (data.size > 0) =>
      notify(StreamEvent.Open)
      notify(StreamEvent.TnBegin)
      data.foreach(notify _)
      notify(StreamEvent.TnCommit)
      notify(StreamEvent.StreamOnline)
      goto(Binded(Active)) replying (Left(()))

    case Event(CloseCmd, _) => stay() replying (Right(new CGateException("Listener already closed")))

    case Event(Dispatch(data), pending) => stay() using (pending ++ data)

    case Event(GetStateCmd, _) => stay() replying (Closed)
  }

  when(Binded(Active)) {
    case Event(CloseCmd, _) =>
      notify(StreamEvent.ReplState(replState))
      notify(StreamEvent.Close)
      goto(Binded(Closed)) replying (Left(()))

    case Event(OpenCmd, _) => stay() replying (Right(new CGateException("Listener already opened")))

    case Event(Dispatch(data), _) =>
      notify(StreamEvent.TnBegin)
      data.foreach(notify _)
      notify(StreamEvent.TnCommit)
      stay()

    case Event(GetStateCmd, _) => stay() replying (Active)
  }

  private def notify(event: StreamEvent) {
    // Subscriber should be never null
    subscriber.get.onMessage(null, null, event)
  }
}