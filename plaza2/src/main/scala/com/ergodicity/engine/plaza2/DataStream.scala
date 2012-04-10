package com.ergodicity.engine.plaza2

import akka.actor.{FSM, Actor}
import akka.util.duration._
import com.ergodicity.engine.plaza2.DataStream.Open
import plaza2.{Connection => P2Connection, DataStream => P2DataStream, _}
import akka.actor.FSM.Failure
import plaza2.StreamState._

sealed trait DataStreamState
object DataStreamState {
  case object Idle extends DataStreamState
  case object Opening extends DataStreamState
  case object Synchronizing extends DataStreamState
  case object Online extends DataStreamState
}


object DataStream {
  case class Open(connection: Connection)

  def apply(underlying: P2DataStream) = new DataStream(underlying)
}

class DataStream(protected[plaza2] val underlying: P2DataStream) extends Actor with FSM[DataStreamState, Option[SafeRelease]] {
  import DataStreamState._

  startWith(Idle, None)

  when(Idle) {
    case Event(Open(connection), None) => goto(Opening) using Some(open(connection.underlying))
  }

  when(Opening, stateTimeout = 10.seconds) {
    case Event(StreamStateChanged(LocalSnapshot), _) => stay()
    case Event(StreamStateChanged(Reopen), _) => stay()
    case Event(StreamLifeNumChanged(ln), _) => log.error("Stream lifenum changed: "+ln); stay()
    case Event(StreamStateChanged(RemoteSnapshot), _) => goto(Synchronizing)

    case Event(FSM.StateTimeout, _) => stop(Failure("DataStream opening timed out"))
  }

  when(Synchronizing) {
    case Event(StreamStateChanged(StreamState.Online), _) => goto(Online)
  }

  when(Online) {
    case Event(StreamDataUpdated(table, id, record), _) => stay()
  }


  onTransition {
    case Idle -> Opening             => log.info("Trying to open DataStream")
    case Opening -> Synchronizing    => log.info("DataStream synchonization started")
    case Synchronizing -> Online     => log.info("DataStream goes Online")
    case transition                  => log.error("Unexpected transition: " + transition)
  }

  whenUnhandled {
    case Event(StreamStateChanged(state@(Close|CloseComplete|StreamState.Reopen|StreamState.Error)), safeRelease) => stop(Failure(state))
  }

  onTermination { case StopEvent(reason, s, d) =>
    log.error("DataStream replication failed, reason = " + reason)
    d foreach {_()}
  }

  initialize

  private def open(connection: P2Connection) = {
    val safeRelease = underlying.dispatchEvents {event =>
      log.info("GOT EVENT: "+event)
      self ! event
    }
    underlying.open(connection)
    safeRelease
  }
}
