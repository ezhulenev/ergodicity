package com.ergodicity.cgate

import akka.util.duration._
import akka.actor.FSM.Failure
import akka.actor.{Cancellable, FSM, Actor}
import ru.micexrts.cgate.{Connection => CGConnection, ErrorCode}

object Connection {

  case object Open

  case object Close

  case object Dispose

  case class StartMessageProcessing(timeout: Int)

  def apply(underlying: CGConnection) = new Connection(underlying)
}

protected[cgate] case class ConnectionState(state: State)

class Connection(protected[cgate] val underlying: CGConnection) extends Actor with FSM[State, Option[Cancellable]] {

  import Connection._

  private var statusTracker: Option[Cancellable] = None

  startWith(Closed, None)

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
    case Event(StartMessageProcessing(timeout), None) =>
      val cancellable = context.system.scheduler.schedule(0 millisecond, 0 millisecond) {
        val res = underlying.process(timeout)
        if (res == ErrorCode.TIMEOUT) {
          log.warning("Timed out on message processing")
        }
      }
      stay() using Some(cancellable)
  }

  onTransition {
    case Closed -> Opening => log.info("Trying to establish connection to CGate router")
    case _ -> Active => log.info("Connection opened")
    case _ -> Closed => log.info("Connection closed")
  }

  whenUnhandled {
    case Event(ConnectionState(Error), _) => stop(Failure("Connection in Error state"))

    case Event(ConnectionState(state), _) if (state != stateName) => goto(state)

    case Event(ConnectionState(state), _) if (state == stateName) => stay()

    case Event(Close, cancellable) =>
      log.info("Close connection")
      statusTracker.foreach(_.cancel())
      statusTracker = None
      cancellable.foreach(_.cancel())
      underlying.close()
      stay()

    case Event(Dispose, cancellable) =>
      log.info("Dispose connection")
      statusTracker.foreach(_.cancel())
      statusTracker = None
      cancellable.foreach(_.cancel())
      underlying.dispose()
      stop(Failure("Disposed"))


    case Event(TrackUnderlyingStatus(duration), _) =>
      statusTracker.foreach(_.cancel())
      statusTracker = Some(context.system.scheduler.schedule(0 milliseconds, duration) {
        self ! ConnectionState(State(underlying.getState))
      })
      stay()
  }

  onTermination {
    case StopEvent(reason, s, d) =>
      log.error("Connection failed, reason = " + reason)
      d foreach {
        _.cancel()
      }
  }

  initialize
}
