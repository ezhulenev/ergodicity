package com.ergodicity.backtest.cgate

import akka.actor._
import akka.dispatch.Await
import akka.pattern.ask
import akka.util
import akka.util.duration._
import com.ergodicity.backtest.cgate.ListenerStubState.Binded
import com.ergodicity.backtest.cgate.ListenerStubState.UnBinded
import com.ergodicity.cgate._
import com.ergodicity.cgate.config.Replication.ReplicationMode.Snapshot
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.core.broker.ReplyEvent
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

object ListenerBindingStub {

  import ListenerStubActor.Command
  import ListenerStubActor.Command._

  def wrap(actor: ActorRef) = {
    implicit val timeout = util.Timeout(1.second)

    def open(i: InvocationOnMock) {
      val config = i.getArguments.apply(0).asInstanceOf[String]
      execCmd(OpenCmd(config))(i)
    }

    def execCmd(cmd: Command)(i: InvocationOnMock) {
      Await.result((actor ? cmd).mapTo[Either[Unit, CGateException]], 1.second) fold(s => s, e => throw e)
    }

    def getState(i: InvocationOnMock) = {
      Await.result((actor ? GetStateCmd).mapTo[State], 1.second).value
    }

    val mock = Mockito.mock(classOf[CGListener])
    doAnswer(open _).when(mock).open(any())
    doAnswer(execCmd(CloseCmd) _).when(mock).close()
    doAnswer(getState _).when(mock).getState
    mock

    new ListenerBinding(subscriber => {
      actor ! Bind(subscriber)
      mock
    })
  }
}


object ListenerStubActor {

  sealed trait Command

  object Command {

    case class OpenCmd(config: String) extends Command

    case object CloseCmd extends Command

    case object GetStateCmd extends Command

    case class Bind(subscriber: ISubscriber) extends Command

  }

}

sealed trait ListenerStubState

object ListenerStubState {

  case object UnBinded extends ListenerStubState

  case class Binded(state: State) extends ListenerStubState

}


object ReplyStreamListenerStubActor {

  case class DispatchReply(reply: ReplyEvent.ReplyData)

  case class DispatchTimeout(timeout: ReplyEvent.TimeoutMessage)

  implicit def toCGateMessage(event: ReplyEvent) = {
    def typeOf(t: Int) = {
      val msg = Mockito.mock(classOf[Message])
      Mockito.when(msg.getType).thenReturn(t)
      msg
    }

    def dataMsg(userId: Int, msgId: Int, data: ByteBuffer) = {
      val message = Mockito.mock(classOf[DataMessage])
      Mockito.when(message.getType).thenReturn(MessageType.MSG_DATA)
      Mockito.when(message.getUserId).thenReturn(userId)
      Mockito.when(message.getMsgId).thenReturn(msgId)
      Mockito.when(message.getData).thenReturn(data)
      message
    }

    def timeoutMsg(userId: Int) = {
      val message = Mockito.mock(classOf[P2MqTimeOutMessage])
      Mockito.when(message.getType).thenReturn(MessageType.MSG_P2MQ_TIMEOUT)
      Mockito.when(message.getUserId).thenReturn(userId)
      message
    }

    import ReplyEvent._
    event match {
      case Open => typeOf(MessageType.MSG_OPEN)
      case ReplyData(userId, messageId, data) => dataMsg(userId, messageId, data)
      case TimeoutMessage(userId) => timeoutMsg(userId)
      case msg@UnsupportedMessage(_) => throw new IllegalArgumentException("Unsupported msg = " + msg)
    }
  }
}

class ReplyStreamListenerStubActor() extends Actor with FSM[ListenerStubState, Seq[ReplyEvent]] {

  import ListenerStubActor.Command._
  import ReplyStreamListenerStubActor._

  private[this] var subscriber: Option[ISubscriber] = None

  startWith(UnBinded, Seq.empty)

  when(UnBinded, stateTimeout = 1.second) {
    case Event(Bind(s), _) =>
      subscriber = Some(s)
      goto(Binded(Closed))

    case Event(DispatchReply(data), pending) => stay() using (pending :+ data)

    case Event(DispatchTimeout(timeout), pending) => stay() using (pending :+ timeout)

    case Event(FSM.StateTimeout, _) => throw new IllegalActorStateException("Timed out in Binding state")

    case e => throw new IllegalActorStateException("Actor is UnBinded; Event = " + e)
  }

  when(Binded(Closed)) {
    case Event(OpenCmd(config), data) =>
      notify(ReplyEvent.Open)
      data.foreach(notify _)
      goto(Binded(Active)) replying (Left(()))

    case Event(CloseCmd, _) => stay() replying (Right(new CGateException("Listener already closed")))

    case Event(DispatchReply(data), pending) => stay() using (pending :+ data)

    case Event(DispatchTimeout(timeout), pending) => stay() using (pending :+ timeout)

    case Event(GetStateCmd, _) => stay() replying (Closed)
  }

  when(Binded(Active)) {
    case Event(CloseCmd, _) => goto(Binded(Closed)) replying (Left(()))

    case Event(OpenCmd(_), _) => stay() replying (Right(new CGateException("Listener already opened")))

    case Event(DispatchReply(data), _) =>
      notify(data)
      stay()

    case Event(DispatchTimeout(timeout), _) =>
      notify(timeout)
      stay()

    case Event(GetStateCmd, _) => stay() replying (Active)
  }

  private def notify(event: ReplyEvent) {
    // Subscriber should be never null
    subscriber.get.onMessage(null, null, event)
  }
}

object ReplicationStreamListenerStubActor {

  case class DispatchData(data: Seq[StreamEvent.StreamData])

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

class ReplicationStreamListenerStubActor(replState: String = "") extends Actor with FSM[ListenerStubState, Seq[StreamEvent.StreamData]] {

  import ReplicationStreamListenerStubActor._
  import ListenerStubActor.Command._

  private[this] var subscriber: Option[ISubscriber] = None

  startWith(UnBinded, Seq.empty)

  when(UnBinded, stateTimeout = 1.second) {
    case Event(Bind(s), _) =>
      subscriber = Some(s)
      goto(Binded(Closed))

    case Event(DispatchData(data), pending) => stay() using (pending ++ data)

    case Event(FSM.StateTimeout, _) => throw new IllegalActorStateException("Timed out in Binding state")

    case e => throw new IllegalActorStateException("Actor is UnBinded; Event = " + e)
  }

  when(Binded(Closed)) {
    case Event(OpenCmd(config), data) =>
      notify(StreamEvent.Open)
      if (data.size > 0) {
        notify(StreamEvent.TnBegin)
        data.foreach(notify _)
        notify(StreamEvent.TnCommit)
      }

      // If we open stream in Snapshot mode close it immediately after dispatching data
      // if not, go to Online state
      if (config.toLowerCase == ReplicationParams(Snapshot).apply()) {
        self ! CloseCmd
      } else {
        notify(StreamEvent.StreamOnline)
      }

      goto(Binded(Active)) replying (Left(()))

    case Event(CloseCmd, _) => stay() replying (Right(new CGateException("Listener already closed")))

    case Event(DispatchData(data), pending) => stay() using (pending ++ data)

    case Event(GetStateCmd, _) => stay() replying (Closed)
  }

  when(Binded(Active)) {
    case Event(CloseCmd, _) =>
      notify(StreamEvent.ReplState(replState))
      notify(StreamEvent.Close)
      goto(Binded(Closed)) replying (Left(()))

    case Event(OpenCmd(_), _) => stay() replying (Right(new CGateException("Listener already opened")))

    case Event(DispatchData(data), _) =>
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