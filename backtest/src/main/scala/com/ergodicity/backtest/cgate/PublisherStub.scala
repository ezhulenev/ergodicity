package com.ergodicity.backtest.cgate

import akka.actor.{ActorRef, FSM, Actor}
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.duration._
import com.ergodicity.backtest.cgate.PublisherStubActor.Command
import com.ergodicity.cgate.scheme.Message
import com.ergodicity.cgate.{Active, Opening, Closed, State}
import java.nio.ByteBuffer
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import ru.micexrts.cgate.messages.DataMessage
import ru.micexrts.cgate.{Publisher => CGPublisher, CGateException}
import scala.Left
import scala.Right

object PublisherStub {

  import Command._

  private[PublisherStub] object DataMessageStub {

    def size(id: Int) = id match {
      case Message.FutAddOrder.MSG_ID => 150
      case Message.OptAddOrder.MSG_ID => 150
      case _ => throw new IllegalArgumentException("Unsupported message id = " + id)
    }

    def apply(msgId: Int) = {
      val buff = ByteBuffer.allocate(size(msgId))
      var userId = 0

      def setUserId(i: InvocationOnMock) {
        userId = i.getArguments.apply(0).asInstanceOf[Int]
      }

      def getUserId(i: InvocationOnMock) = userId

      val mock = Mockito.mock(classOf[DataMessage])
      doAnswer(setUserId _).when(mock).setUserId(any())
      doAnswer(getUserId _).when(mock).getUserId
      when(mock.getData).thenReturn(buff)
      when(mock.getMsgId).thenReturn(msgId)
      mock
    }
  }

  def wrap(actor: ActorRef) = {
    implicit val timeout = akka.util.Timeout(1.second)

    def execCmd(cmd: Command)(i: InvocationOnMock) {
      Await.result((actor ? cmd).mapTo[Either[Unit, CGateException]], 1.second) fold(s => s, e => throw e)
    }

    def getState(i: InvocationOnMock) = {
      Await.result((actor ? GetStateCmd).mapTo[Int], 1.second)
    }

    def newMessage(i: InvocationOnMock) = {
      val msgId = i.getArguments.apply(1).asInstanceOf[Int]
      DataMessageStub(msgId)
    }

    val mock = Mockito.mock(classOf[CGPublisher])
    doAnswer(execCmd(OpenCmd) _).when(mock).open(any())
    doAnswer(execCmd(CloseCmd) _).when(mock).close()
    doAnswer(getState _).when(mock).getState
    doAnswer(newMessage _).when(mock).newMessage(any(), any())
    mock
  }
}


object PublisherStubActor {

  sealed trait Command

  object Command {

    case object OpenCmd extends Command

    case object CloseCmd extends Command

    case object GetStateCmd extends Command

    case class Post(message: ru.micexrts.cgate.messages.Message)

  }

}

class PublisherStubActor extends Actor with FSM[State, Unit] {

  import PublisherStubActor.Command._

  startWith(Closed, ())

  when(Closed) {
    case Event(OpenCmd, _) => goto(Opening) replying (Left(()))

    case Event(CloseCmd, _) => stay() replying (Right(new CGateException("Publisher already closed")))
  }

  when(Opening, stateTimeout = 50.millis) {
    case Event(CloseCmd, _) => goto(Closed) replying (Left(()))

    case Event(OpenCmd, _) => stay() replying (Right(new CGateException("Publisher already opened")))

    case Event(FSM.StateTimeout, _) => goto(Active)
  }

  when(Active) {
    case Event(CloseCmd, _) => goto(Closed) replying (Left(()))

    case Event(OpenCmd, _) => stay() replying (Right(new CGateException("Publisher already opened")))
  }

  whenUnhandled {
    case Event(GetStateCmd, _) => stay() replying (stateName.value)
  }
}

