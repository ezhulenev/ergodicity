package com.ergodicity.engine.core

import com.ergodicity.engine.plaza2.scheme.FutInfo._
import com.ergodicity.engine.plaza2.DataStream.JoinTable
import com.ergodicity.engine.plaza2.Repository
import com.ergodicity.engine.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import akka.event.Logging
import akka.actor.{PoisonPill, Props, Actor, ActorRef}
import model.Session.{OptInfoSessionContents, FutInfoSessionContents}
import model.{Session, SessionState, IntClearingState, SessionContent}
import com.ergodicity.engine.plaza2.scheme.{OptInfo, Deserializer, FutInfo}
import com.ergodicity.engine.core.Sessions.{JoinOptInfoRepl, JoinFutInfoRepl, OngoingSession, GetOngoingSession}

protected[core] case class SessionId(id: Long, optionSessionId: Long)

object Sessions {
  def apply = new Sessions()

  case object GetOngoingSession

  case class OngoingSession(session: Option[ActorRef])

  case class JoinFutInfoRepl(dataStream: ActorRef)
  case class JoinOptInfoRepl(dataStream: ActorRef)
}

class Sessions extends Actor {
  val log = Logging(context.system, this)

  protected[core] var ongoingSession: Option[ActorRef] = None
  protected[core] var trackingSessions: Map[SessionId, ActorRef] = Map()

  // Repositories
  val sessionRepository = context.actorOf(Props(Repository[SessionRecord]), "SessionRepository")
  sessionRepository ! SubscribeSnapshots(self)

  val futSessContentsRepository = context.actorOf(Props(Repository[FutInfo.SessContentsRecord]), "FutSessContentsRepository")
  futSessContentsRepository ! SubscribeSnapshots(self)

  val optSessContentsRepository = context.actorOf(Props(Repository[OptInfo.SessContentsRecord]), "OptSessContentsRepository")
  optSessContentsRepository ! SubscribeSnapshots(self)

  protected def receive = {
    case GetOngoingSession => sender ! OngoingSession(ongoingSession)

    case JoinFutInfoRepl(dataStream) =>
      dataStream ! JoinTable(sessionRepository, "session", implicitly[Deserializer[SessionRecord]])
      dataStream ! JoinTable(futSessContentsRepository, "fut_sess_contents", implicitly[Deserializer[FutInfo.SessContentsRecord]])

    case JoinOptInfoRepl(dataStream) =>
      dataStream ! JoinTable(optSessContentsRepository, "opt_sess_contents", implicitly[Deserializer[OptInfo.SessContentsRecord]])

    case Snapshot(repo, data: Iterable[SessionRecord]) if (repo == sessionRepository) =>
      updateSessions(data)
      futSessContentsRepository ! SubscribeSnapshots(self)

    case snapshot@Snapshot(repo, _) if (repo == futSessContentsRepository) =>
      handleFutSessContents(snapshot.asInstanceOf[Snapshot[FutInfo.SessContentsRecord]])

    case snapshot@Snapshot(repo, _) if (repo == optSessContentsRepository) =>
      handleOptSessContents(snapshot.asInstanceOf[Snapshot[OptInfo.SessContentsRecord]])
  }

  protected def handleOptSessContents(snapshot: Snapshot[OptInfo.SessContentsRecord]) {
    trackingSessions.foreach {case (SessionId(_, id), session)=>
      session ! OptInfoSessionContents(snapshot.filter(_.sessId == id))
    }
  }

  protected def handleFutSessContents(snapshot: Snapshot[FutInfo.SessContentsRecord]) {
    trackingSessions.foreach {case (SessionId(id, _), session)=>
      session ! FutInfoSessionContents(snapshot.filter(_.sessId == id))
    }
  }

  protected def updateSessions(records: Iterable[SessionRecord]) {
    val (alive, outdated) = trackingSessions.partition {
      case (SessionId(i1, i2), ref) => records.find((r: SessionRecord) => r.sessionId == i1 && r.optionsSessionId == i2).isDefined
    }

    // Kill all outdated sessions
    outdated.foreach {
      case (id, session) => session ! PoisonPill
    }

    // Update status for still alive sessions
    alive.foreach {
      case (SessionId(i1, i2), session) =>
        records.find((r: SessionRecord) => r.sessionId == i1 && r.optionsSessionId == i2) foreach {
          record =>
            session ! SessionState(record.state)
            session ! IntClearingState(record.interClState)
        }
    }

    // Create actors for new sessions
    val newSessions = records.filter(record => !alive.contains(SessionId(record.sessionId, record.optionsSessionId))).map {
      newRecord =>
        val sessionId = newRecord.sessionId
        val state = SessionState(newRecord.state)
        val intClearingState = IntClearingState(newRecord.interClState)
        val content = new SessionContent(newRecord)
        val session = context.actorOf(Props(new Session(content, state, intClearingState)), sessionId.toString)

        SessionId(sessionId, newRecord.optionsSessionId) -> session
    }

    // Update internal state
    trackingSessions = alive ++ newSessions
    ongoingSession = records.filter {
      record => SessionState(record.state) match {
        case SessionState.Completed | SessionState.Canceled => false
        case _ => true
      }
    }.headOption.flatMap {
      record => trackingSessions.get(SessionId(record.sessionId, record.optionsSessionId))
    }
  }
}