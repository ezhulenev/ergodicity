package com.ergodicity.cgate

import akka.util.duration._
import akka.pattern.ask
import akka.actor.FSM.Failure
import akka.actor.{Cancellable, FSM, Actor}
import ru.micexrts.cgate.{Connection => CGConnection}
import akka.util.{Timeout, Duration}

object Connection {

  case object Open

  case object Close

  case object Dispose

  case class StartMessageProcessing(timeout: Duration)

  case class Execute[T](f: CGConnection => T)

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

  implicit val timeout = Timeout(1.second)

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

    case Event(Execute(f), _) =>
      sender ! f(underlying)
      stay()

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

    case Event(track@TrackUnderlyingStatus(duration), _) =>
      statusTracker.foreach(_.cancel())
      statusTracker = Some(context.system.scheduler.schedule(0 milliseconds, duration) {
        (self ? Execute(_.getState)).mapTo[Int] onSuccess {
          case state => self ! ConnectionState(State(state))
        }
      })
      stay()
  }

  onTermination {
    case StopEvent(reason, s, d) =>
      log.error("Connection failed, reason = " + reason)
  }

  initialize
}
