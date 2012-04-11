package com.ergodicity.engine.plaza2

import akka.util.duration._
import plaza2.{Connection => P2Connection, DataStream => P2DataStream, Record => _, _}
import akka.actor.FSM.Failure
import plaza2.StreamState._
import java.io.File
import akka.actor.{ActorRef, FSM, Actor}
import com.ergodicity.engine.plaza2.DataStream.{JoinTable, SetLifeNumToIni, Open}

sealed trait DataStreamState
object DataStreamState {
  case object Idle extends DataStreamState
  case object Opening extends DataStreamState
  case object Reopen extends DataStreamState
  case object Synchronizing extends DataStreamState
  case object Online extends DataStreamState
}


object DataStream {
  case class Open(connection: Connection)
  case class SetLifeNumToIni(ini: File)
  case class JoinTable(ref: ActorRef,  table: String)

  def apply(underlying: P2DataStream) = new DataStream(underlying)
}

class DataStream(protected[plaza2] val underlying: P2DataStream) extends Actor with FSM[DataStreamState, Option[SafeRelease]] {
  import DataStreamState._

  private var setLifeNumToIni: Option[File] = None
  private var tableDataEventsListeners = Seq[(String, ActorRef)]()

  startWith(Idle, None)

  when(Idle) {
    case Event(Open(connection), None) => goto(Opening) using Some(open(connection.underlying))
    case Event(SetLifeNumToIni(file), _) => setLifeNumToIni = Some(file); stay()
    case Event(JoinTable(ref, table), _) => tableDataEventsListeners = (table, ref) +: tableDataEventsListeners; stay()
  }

  when(Opening, stateTimeout = 10.seconds) {
    case Event(StreamStateChanged(StreamState.LocalSnapshot), _) => goto(Synchronizing)
    case Event(FSM.StateTimeout, _) => stop(Failure("DataStream opening timed out"))
  }

  when(Reopen) {
    case Event(StreamStateChanged(StreamState.RemoteSnapshot), _) => goto(Synchronizing)
    case Event(StreamLifeNumChanged(lifeNum), _) => updateStreamLifeNumber(lifeNum)
  }

  when(Synchronizing) {
      case Event(StreamStateChanged(StreamState.Reopen), _) => goto(Reopen)
      case Event(StreamStateChanged(StreamState.RemoteSnapshot), _) => stay()
      case Event(StreamStateChanged(StreamState.Online), _) => goto(Online)
  }

  when(Synchronizing) {
    handleStreamDataEvents
  }
  
  when(Online) {
    case Event(StreamStateChanged(StreamState.Reopen), _) => goto(Reopen)
  }

  when(Online) {
    handleStreamDataEvents
  }

  onTransition {
    case Idle -> Opening             => log.info("Trying to open DataStream")
    case _ -> Synchronizing          => log.info("DataStream synchonization started")
    case _ -> Reopen                 => log.info("DataStream reopened")
    case Synchronizing -> Online     => log.info("DataStream goes Online")
    case transition                  => log.error("Unexpected transition: " + transition)
  }

  whenUnhandled {
    case Event(StreamStateChanged(state@(Close|CloseComplete|StreamState.Error)), safeRelease) => stop(Failure(state))
  }

  onTermination { case StopEvent(reason, s, d) =>
    log.error("DataStream replication failed, reason = " + reason)
    d foreach {_()}
  }

  initialize
  
  private def handleStreamDataEvents: StateFunction = {
    case Event(e@StreamDataBegin, _)                  => tableDataEventsListeners.foreach(_._2 ! e); stay()
    case Event(e@StreamDataEnd, _)                    => tableDataEventsListeners.foreach(_._2 ! e); stay()
    case Event(e@StreamDatumDeleted(table, _), _)     => tableDataEventsListeners.filter(_._1 == table).foreach(_._2 ! e); stay()
    case Event(e@StreamDataInserted(table, _), _)     => tableDataEventsListeners.filter(_._1 == table).foreach(_._2 ! e); stay()
    case Event(e@StreamDataDeleted(table, _, _), _)   => tableDataEventsListeners.filter(_._1 == table).foreach(_._2 ! e); stay()
  }

  private def open(connection: P2Connection) = {
    val safeRelease = underlying.dispatchEvents {
      self ! _
    }
    underlying.open(connection)
    safeRelease
  }

  private def updateStreamLifeNumber(lifeNum: Long) = {
    setLifeNumToIni.map {file =>
        log.debug("Update stream life number up to " + lifeNum)
        underlying.tableSet.lifeNum = lifeNum
        underlying.tableSet.setLifeNumToIni(file)
        stay()
    } getOrElse stop(Failure("SetLifeNumToIni is not defined, life num updated up to = " + lifeNum))
  }
}