package com.ergodicity.engine.plaza2.futures

import com.ergodicity.engine.plaza2.scheme.SessionRecord
import com.ergodicity.engine.plaza2.scheme.FutInfo._
import com.ergodicity.engine.plaza2.DataStream.JoinTable
import com.ergodicity.engine.plaza2.Repository
import com.ergodicity.engine.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import akka.event.Logging
import akka.actor.{PoisonPill, Props, Actor, ActorRef}

object Sessions {
  def apply(dataStream: ActorRef) = new Sessions(dataStream)
}

class Sessions(dataStream: ActorRef) extends Actor {
  val log = Logging(context.system, this)

  val repository = context.actorOf(Props(Repository[SessionRecord]))
  repository ! SubscribeSnapshots(self)
  dataStream ! JoinTable(repository, "session")

  protected[futures] var ongoingSession: Option[ActorRef] = None
  protected[futures] var trackingSessions: Map[Long, ActorRef] = Map()

  protected def receive = {
    case Snapshot(records: Iterable[SessionRecord]) => updateSessions(records)
  }

  private def updateSessions(records: Iterable[SessionRecord]) {
    val (alive, outdated) = trackingSessions.partition {
      case (id, ref) => records.find(_.sessionId == id).isDefined
    }

    // Kill all outdated sessions
    outdated.foreach {
      case (id, session) => session ! PoisonPill
    }

    // Update status for still alive sessions
    alive.foreach {
      case (id, session) =>
        records.find(_.sessionId == id) foreach {
          record =>
            session ! SessionState(record.state)
            session ! IntClearingState(record.interClState)
        }
    }

    // Create actors for new sessions
    val newSessions = records.filter(record => !alive.contains(record.sessionId)).map {
      newRecord =>
        val id = newRecord.sessionId
        val state = SessionState(newRecord.state)
        val intClearingState = IntClearingState(newRecord.interClState)
        val content = new SessionContent(newRecord)
        val session = context.actorOf(Props(new Session(content, state, intClearingState)), id.toString)

        id -> session
    }

    // Update internal state
    trackingSessions = alive ++ newSessions
    ongoingSession = records.filter {
      record => SessionState(record.state) match {
        case SessionState.Completed | SessionState.Canceled => false
        case _ => true
      }
    }.headOption.flatMap {
      record => trackingSessions.get(record.sessionId)
    }
  }
}