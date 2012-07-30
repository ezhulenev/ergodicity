package com.ergodicity.cgate

import akka.util.duration._
import config.ListenerOpenParams
import ru.micexrts.cgate.{Listener => CGListener}
import akka.actor.FSM.Failure
import akka.actor.{Cancellable, Actor, FSM}

object Listener {

  case class Open(config: ListenerOpenParams)

  case object Close

  case object Dispose

}

protected[cgate] case class ListenerState(state: State)

class Listener(underlying: CGListener) extends Actor with FSM[State, Option[ListenerOpenParams]] {

  import Listener._

  private var statusTracker: Option[Cancellable] = None

  startWith(Closed, None)

  when(Closed) {
    case Event(Open(config), None) =>
      log.info("Open listener with config = " + config)
      underlying.open(config.config)
      stay() using Some(config)
  }

  when(Opening, stateTimeout = 3.second) {
    case Event(FSM.StateTimeout, _) => stop(Failure("Connecting timeout"))
  }

  when(Active) {
    case Event(Close, _) =>
      log.info("Close Listener")
      statusTracker.foreach(_.cancel())
      statusTracker = None
      underlying.close()
      goto(Closed) using None
  }

  onTransition {
    case Closed -> Opening => log.info("Opening listener")
    case _ -> Active => log.info("Listener opened")
    case _ -> Closed => log.info("Listener closed")
  }

  whenUnhandled {
    case Event(ListenerState(Error), _) => stop(Failure("Listener in Error state"))

    case Event(ListenerState(state), _) if (state != stateName) => goto(state)

    case Event(ListenerState(state), _) if (state == stateName) => stay()

    case Event(TrackUnderlyingStatus(duration), _) =>
      statusTracker.foreach(_.cancel())
      statusTracker = Some(context.system.scheduler.schedule(0 milliseconds, duration) {
        self ! ListenerState(State(underlying.getState))
      })
      stay()

    case Event(Dispose, _) =>
      log.info("Dispose listener")
      statusTracker.foreach(_.cancel())
      statusTracker = None
      underlying.dispose()
      stop(Failure("Disposed"))
  }

  onTermination {
    case StopEvent(reason, s, d) => log.error("Listener failed, reason = " + reason)
  }

  initialize
}