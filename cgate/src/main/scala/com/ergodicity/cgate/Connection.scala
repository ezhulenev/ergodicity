package com.ergodicity.cgate

import akka.util.duration._
import akka.actor.FSM.Failure
import akka.actor.{Cancellable, FSM, Actor}
import ru.micexrts.cgate.{Connection => CGConnection}
import akka.util.Duration

object Connection {

  case object Open

  case object Close

  case object Dispose

  case class StartMessageProcessing(timeout: Duration)

  private[Connection] case class ProcessMessages(timeout: Duration)

  def apply(underlying: CGConnection) = new Connection(underlying)
}

protected[cgate] case class ConnectionState(state: State)

protected[cgate] sealed trait MessageProcessingState

protected[cgate] object MessageProcessingState {

  case object On extends MessageProcessingState

  case object Off extends MessageProcessingState

}

class Connection(protected[cgate] val underlying: CGConnection) extends Actor with FSM[State, MessageProcessingState] {

  import Connection._
  import MessageProcessingState._

  private var statusTracker: Option[Cancellable] = None

  startWith(Closed, Off)

  when(Closed) {
    case Event(Open, _) =>
      log.info("Open connection")
      underlying.open("")
      stay()
  }

  when(Opening, stateTimeout = 3.second) {
    case Event(FSM.StateTimeout, _) => stop(Failure("Connecting timeout"))
  }

  when(Active) {
    case Event(StartMessageProcessing(timeout), off) =>
      self ! ProcessMessages(timeout)
      stay() using On
  }

  onTransition {
    case Closed -> Opening => log.info("Trying to establish connection to CGate router")
    case _ -> Active => log.info("Connection opened")
    case _ -> Closed => log.info("Connection closed")
  }

  whenUnhandled {
    case Event(ConnectionState(Error), _) => stop(Failure("Connection in Error state")) using Off

    case Event(ConnectionState(state), _) if (state != stateName) => goto(state)

    case Event(ConnectionState(state), _) if (state == stateName) => stay()

    case Event(Close, _) =>
      log.info("Close connection")
      statusTracker.foreach(_.cancel())
      statusTracker = None
      underlying.close()
      stay() using Off

    case Event(Dispose, cancellable) =>
      log.info("Dispose connection")
      statusTracker.foreach(_.cancel())
      statusTracker = None
      underlying.dispose()
      stop(Failure("Disposed")) using Off

    case Event(pm@ProcessMessages(timeout), On) =>
      underlying.process(timeout.toMillis.toInt)
      self ! pm
      stay()

    case Event(UpdateUnderlyingStatus, _) =>
      self ! ConnectionState(State(underlying.getState))
      stay()

    case Event(track@TrackUnderlyingStatus(duration), _) =>
      statusTracker.foreach(_.cancel())
      statusTracker = Some(context.system.scheduler.schedule(0 milliseconds, duration) {
        self ! UpdateUnderlyingStatus
      })
      stay()
  }

  onTermination {
    case StopEvent(reason, s, d) =>
      log.error("Connection failed, reason = " + reason)
  }

  initialize
}
