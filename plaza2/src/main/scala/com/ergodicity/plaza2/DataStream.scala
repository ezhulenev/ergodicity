package com.ergodicity.plaza2

import akka.util.duration._
import plaza2.{Connection => P2Connection, DataStream => P2DataStream, Record => _, _}
import akka.actor.FSM.Failure
import plaza2.StreamState._
import java.io.File
import akka.actor.{ActorRef, FSM, Actor}
import com.ergodicity.plaza2.DataStream._
import com.twitter.ostrich.stats.Stats
import com.ergodicity.plaza2.scheme.{Deserializer, Record}

sealed trait DataStreamState

object DataStreamState {

  case object Idle extends DataStreamState

  case object Opening extends DataStreamState

  case object Reopen extends DataStreamState

  case object Synchronizing extends DataStreamState

  case object Online extends DataStreamState

}


object DataStream {

  case class Open(connection: P2Connection)

  case class BindTable[R <: Record](table: String, ref: ActorRef, deserializer: Deserializer[R])

  // Life Number updates
  case class SetLifeNumToIni(ini: File)

  case class SubscribeLifeNumChanges(ref: ActorRef)

  case class LifeNumChanged(dataStream: ActorRef, lifeNum: Long)

  // Data Events
  sealed trait DataEvent

  case object DataBegin extends DataEvent

  case object DataEnd extends DataEvent

  case class DatumDeleted(table: String, repLRev: Long) extends DataEvent

  case class DataInserted[R <: Record](table: String, record: R) extends DataEvent

  case class DataDeleted(table: String, replId: Long) extends DataEvent

  def apply(underlying: P2DataStream) = new DataStream(underlying)
}

class DataStream(protected[plaza2] val underlying: P2DataStream, ini: Option[File] = None) extends Actor with FSM[DataStreamState, Option[SafeRelease]] {

  import DataStreamState._

  private var lifeNumSubscribers = Seq[ActorRef]()
  private var setLifeNumToIni: Option[File] = ini

  @volatile
  private var tableListeners = Map[String, (ActorRef, Deserializer[_ <: Record])]()

  startWith(Idle, None)

  when(Idle) {
    case Event(Open(connection), None) => goto(Opening) using Some(open(connection))

    case Event(SetLifeNumToIni(file), _) => setLifeNumToIni = Some(file); stay()

    case Event(SubscribeLifeNumChanges(ref), _) => lifeNumSubscribers = ref +: lifeNumSubscribers; stay()

    case Event(BindTable(table, ref, deserializer), _) if (!tableListeners.contains(table)) =>
      tableListeners += (table ->(ref, deserializer)); stay()
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

  when(Online) {
    case Event(StreamStateChanged(StreamState.Reopen), _) => goto(Reopen)
  }

  onTransition {
    case Idle -> Opening => log.info("Trying to open DataStream")
    case _ -> Synchronizing => log.info("DataStream synchonization started")
    case _ -> Reopen => log.info("DataStream reopened"); notifyStreamReopened()
    case Synchronizing -> Online => log.info("DataStream goes Online")
    case transition => log.error("Unexpected transition: " + transition)
  }

  whenUnhandled {
    case Event(StreamStateChanged(state@(Close | CloseComplete | StreamState.Error)), safeRelease) => stop(Failure(state))
  }

  onTermination {
    case StopEvent(reason, s, d) =>
      log.error("DataStream replication failed, reason = " + reason)
      d foreach {
        release => if (release != null) release()
      }
  }

  initialize

  private def notifyStreamReopened() {
    tableListeners.foreach {
      case (table, (ref, _)) =>
        ref ! DataBegin
        ref ! DatumDeleted(table, 0)
        ref ! DataEnd
    }
  }

  private def open(connection: P2Connection) = {
    log.debug("Open stream using connection: " + connection)
    val safeRelease = underlying.dispatchEvents {
      // First handle Data events
      case StreamDataBegin => tableListeners.foreach {
        case (_, (ref, _)) => ref ! DataBegin
      }
      case StreamDataEnd => tableListeners.foreach {
        case (_, (ref, _)) => ref ! DataEnd
      }

      case StreamDatumDeleted(table, replRev) => tableListeners.get(table).foreach {
        case (ref, _) => ref ! DatumDeleted(table, replRev)
      }
      case StreamDataInserted(table, record) =>
        Stats.incr(self.path + "/StreamDataInserted")
        tableListeners.get(table).foreach {
          case (ref, ds) => ref ! DataInserted(table, ds(record))
        }
      case StreamDataDeleted(table, replId, _) => tableListeners.get(table).foreach {
        case (ref, _) => ref ! DataDeleted(table, replId)
      }

      // All other events send to self
      case event => self ! event
    }
    underlying.open(connection)
    safeRelease
  }

  private def updateStreamLifeNumber(lifeNum: Long) = {
    lifeNumSubscribers.foreach(_ ! LifeNumChanged(self, lifeNum))

    setLifeNumToIni.map {
      file =>
        log.debug("Update stream life number up to " + lifeNum)
        underlying.tableSet.lifeNum = lifeNum
        underlying.tableSet.setLifeNumToIni(file)
        stay()
    } getOrElse stop(Failure("SetLifeNumToIni is not defined, life num updated up to = " + lifeNum))
  }
}