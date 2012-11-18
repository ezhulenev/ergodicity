package com.ergodicity.backtest.cgate

import akka.actor.{ActorRef, FSM, Actor}
import com.ergodicity.cgate._
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.invocation.InvocationOnMock
import akka.util
import akka.util.duration._
import akka.pattern.ask
import akka.dispatch.Await
import ru.micexrts.cgate.{Connection => CGConnection, CGateException}
import com.ergodicity.backtest.cgate.ConnectionStubActor.Command
import util.Duration
import scala.Left
import scala.Right

object ConnectionStub {

  import Command._

  def wrap(actor: ActorRef) = {
    implicit val timeout = util.Timeout(1.second)

    def execCmd(cmd: Command)(i: InvocationOnMock) {
      Await.result((actor ? cmd).mapTo[Either[Unit, CGateException]], 1.second) fold(s => s, e => throw e)
    }

    def getState(i: InvocationOnMock) = {
      Await.result((actor ? GetStateCmd).mapTo[Int], 1.second)
    }

    def sleep(duration: Duration)(i: InvocationOnMock) {
      Thread.sleep(duration.toMillis)
    }

    val mock = Mockito.mock(classOf[CGConnection])
    doAnswer(execCmd(OpenCmd) _).when(mock).open(any())
    doAnswer(execCmd(CloseCmd) _).when(mock).close()
    doAnswer(getState _).when(mock).getState
    doAnswer(sleep(500.millis) _).when(mock).process(any())
    mock
  }
}

object ConnectionStubActor {

  sealed trait Command

  object Command {

    case object OpenCmd extends Command

    case object CloseCmd extends Command

    case object GetStateCmd extends Command

  }
}

class ConnectionStubActor extends Actor with FSM[State, Unit] {

  import ConnectionStubActor.Command._

  startWith(Closed, ())

  when(Closed) {
    case Event(OpenCmd, _) => goto(Opening) replying (Left(()))

    case Event(CloseCmd, _) => stay() replying (Right(new CGateException("Connection already closed")))
  }

  when(Opening, stateTimeout = 1.second) {
    case Event(CloseCmd, _) => goto(Closed) replying (Left(()))

    case Event(OpenCmd, _) => stay() replying (Right(new CGateException("Connection already opened")))

    case Event(FSM.StateTimeout, _) => goto(Active)
  }

  when(Active) {
    case Event(CloseCmd, _) => goto(Closed) replying (Left(()))

    case Event(OpenCmd, _) => stay() replying (Right(new CGateException("Connection already opened")))
  }

  whenUnhandled {
    case Event(GetStateCmd, _) => stay() replying (stateName.value)
  }
}

