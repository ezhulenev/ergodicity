package com.ergodicity.cgate

import akka.util.duration._
import akka.actor.FSM.Failure
import akka.actor.{FSM, Actor}
import ru.micexrts.cgate.{Connection => CGConnection}
import akka.util.{Timeout, Duration}

object Connection {

  case object Open

  case object Close

  case object Dispose

  case object UpdateState

  case class StartMessageProcessing(timeout: Duration)

  private[Connection] case class ProcessMessages(timeout: Duration)

  def apply(underlying: CGConnection, updateStateDuration: Option[Duration] = Some(1.second)) = new Connection(underlying, updateStateDuration)

  case class ConnectionTimedOut() extends RuntimeException

  case class ConnectionError() extends RuntimeException

}

protected[cgate] case class ConnectionState(state: State)

protected[cgate] sealed trait MessageProcessingState

protected[cgate] object MessageProcessingState {

  case object On extends MessageProcessingState

  case object Off extends MessageProcessingState

}

class Connection(protected[cgate] val underlying: CGConnection, updateStateDuration: Option[Duration] = Some(1.second)) extends Actor with FSM[State, MessageProcessingState] {

  import Connection._
  import MessageProcessingState._

  implicit val timeout = Timeout(1.second)

  private val statusTracker = updateStateDuration.map {duration =>
    context.system.scheduler.schedule(0 milliseconds, duration) {
      self ! UpdateState
    }
  }

  startWith(Closed, Off)

  when(Closed) {
    case Event(Open, _) =>
      log.info("Open connection")
      underlying.open("")
      stay()
  }

  when(Opening, stateTimeout = 3.second) {
    case Event(FSM.StateTimeout, _) => throw new ConnectionTimedOut
  }

  when(Active) {
    case Event(StartMessageProcessing(t), off) =>
      self ! ProcessMessages(t)
      stay() using On
  }

  onTransition {
    case Closed -> Opening => log.info("Trying to establish connection to CGate router")
    case _ -> Active => log.info("Connection opened")
    case _ -> Closed => log.info("Connection closed")
  }

  whenUnhandled {
    case Event(ConnectionState(Error), _) => throw new ConnectionError

    case Event(ConnectionState(state), _) if (state != stateName) => goto(state)

    case Event(ConnectionState(state), _) if (state == stateName) => stay()

    case Event(UpdateState, _) =>
      self ! ConnectionState(State(underlying.getState))
      stay()

    case Event(Close, _) =>
      log.info("Close connection")
      underlying.close()
      stay() using Off

    case Event(Dispose, cancellable) =>
      log.info("Dispose connection")
      underlying.dispose()
      stop(Failure("Disposed")) using Off

    case Event(pm@ProcessMessages(t), On) =>
      underlying.process(t.toMillis.toInt)
      self ! pm
      stay()
  }

  onTermination {
    case StopEvent(reason, s, d) =>
      log.error("Connection failed, reason = " + reason)
  }

  initialize

  override def postStop() {
    statusTracker.foreach(_.cancel())
    super.postStop()
  }
}
