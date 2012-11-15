package com.ergodicity.backtest.cgate

import akka.actor.{ActorRef, FSM, Actor}
import akka.dispatch.Await
import akka.pattern.ask
import akka.util
import akka.util.duration._
import com.ergodicity.backtest.Writes
import com.ergodicity.cgate.{Active, Closed, State}
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import ru.micexrts.cgate.messages.{Message, StreamDataMessage}
import ru.micexrts.cgate.{Listener => CGListener, MessageType, CGateException, ISubscriber}

object Listener {

  object Messages {
    val Open = typeOf(MessageType.MSG_OPEN)
    val Close = typeOf(MessageType.MSG_CLOSE)

    private[Messages] def typeOf(t: Int) = {
      val msg = Mockito.mock(classOf[Message])
      Mockito.when(msg.getType).thenReturn(t)
      msg
    }
  }

  case class Payload[P](msgIndex: Int, msg: P)(implicit writes: Writes[P]) {
    val streamDataMessage = {
      val message = Mockito.mock(classOf[StreamDataMessage])
      Mockito.when(message.getType).thenReturn(MessageType.MSG_STREAM_DATA)
      Mockito.when(message.getMsgIndex).thenReturn(msgIndex)
      Mockito.when(message.getData).thenReturn(writes(msg))
      message
    }
  }

  private[cgate] case object Open

  implicit def toAnswer[A](f: InvocationOnMock => A) = new Answer[A] {
    def answer(invocation: InvocationOnMock) = f(invocation)
  }


  // Create wrapper over backtest Listener actor
  def apply(actor: ActorRef) = {
    implicit val timeout = util.Timeout(1.second)

    def open(i: InvocationOnMock) {
      Await.result((actor ? Open).mapTo[Either[Unit, CGateException]], 1.second) fold(_ => (), e => throw e)
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
    case Event(Open, _) => goto(Active) replying (Left(()))
  }

  when(Active) {
    case Event(Open, _) => stay() replying (Right(new CGateException("Listener already opened")))
  }
}