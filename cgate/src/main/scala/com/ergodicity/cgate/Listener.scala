package com.ergodicity.cgate

import akka.actor.{Actor, FSM}
import akka.util.duration._
import com.ergodicity.cgate.StreamState.Offline
import config.Replication.ReplicationMode
import ru.micexrts.cgate.{Listener => CGListener, ErrorCode}
import ru.micexrts.cgate.messages.Message

sealed trait StreamState

object StreamState {

  case object Offline extends StreamState

  case object Online extends StreamState

}


object Listener {
  val StateUpdateTimeOut = 1.second

  sealed trait OpenParams {
    def config: String
  }

  case class Replication(mode: ReplicationMode, state: Option[String] = None) {
    private val modeParam = "mode=" + mode.name
    val config = state.map(modeParam + ";replstate=" + _).getOrElse(modeParam)
  }

  case class Open(params: OpenParams)

  case object Close

}

protected[cgate] case class ListenerState(state: State)

class Listener(listener: Subscriber => CGListener, initialState: Option[String] = None) extends Actor with FSM[State, StreamState] {

  import Listener._

  val subscriber = new Subscriber {
    def handleMessage(msg: Message) = {
      log.info("Got message = " + msg)
      ErrorCode.OK
    }
  }

  val underlying = listener(subscriber)

  startWith(Closed, Offline)

  when(Closed) {
    case Event(Open(params), Offline) =>
      log.info("Open listener with params = " + params)
      underlying.open(params.config)
      stay()
  }

  onTransition {
    case Closed -> Opening => log.info("Openin listener")
    case _ -> Active => log.info("Successfully opened listener")
    case Active -> err => log.error("Listener failed; Moved to state " + err)
  }

  whenUnhandled {
    case Event(ListenerState(state), _) if (state != stateName) =>
      log.debug("Listener state changed to " + state)
      goto(state)

    case Event(Close, _) =>
      log.info("Close Listener")
      underlying.close()
      stay()

    case Event(ListenerState(state), _) if (state == stateName) => stay()
  }

  onTermination {
    case StopEvent(reason, s, d) => log.error("Listener failed, reason = " + reason)
  }

  initialize

  // Subscribe for listener state updates
  context.system.scheduler.schedule(0 milliseconds, StateUpdateTimeOut) {
    self ! ListenerState(State(underlying.getState))
  }

}
