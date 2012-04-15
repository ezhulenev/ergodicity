package com.ergodicity.engine.core

import com.ergodicity.engine.plaza2.scheme.FutInfo._
import com.ergodicity.engine.plaza2.DataStream.JoinTable
import com.ergodicity.engine.plaza2.Repository
import com.ergodicity.engine.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import akka.event.Logging
import akka.actor.{PoisonPill, Props, Actor, ActorRef}
import com.ergodicity.engine.core.Sessions.{OngoingSession, GetOngoingSession}
import model.Session.FutInfoSessionContents
import model.{Session, SessionState, IntClearingState, SessionContent}
import com.ergodicity.engine.plaza2.scheme.FutInfo
import akka.testkit.TestActorRef._
import akka.testkit.TestActorRef

object Sessions {
  def apply(dataStream: ActorRef) = new Sessions(dataStream)

  case object GetOngoingSession

  case class OngoingSession(session: Option[ActorRef])

}

class Sessions(dataStream: ActorRef) extends Actor {
  val log = Logging(context.system, this)

  protected[core] var ongoingSession: Option[ActorRef] = None
  protected[core] var trackingSessions: Map[Long, ActorRef] = Map()

  // Handle 'session' table
  val sessionRepository = context.actorOf(Props(Repository[SessionRecord]), "SessionRepository")
  sessionRepository ! SubscribeSnapshots(self)
  dataStream ! JoinTable(sessionRepository, "session")

  // Handle 'fut_sess_contents' table
  val futSessContentsRepository = context.actorOf(Props(Repository[FutInfo.SessContentsRecord](FutInfo.SessContentsDeserializer)), "FutSessContentesRepository")
  futSessContentsRepository ! SubscribeSnapshots(self)
  dataStream ! JoinTable(futSessContentsRepository, "fut_sess_contents")

  futSessContentsRepository ! SubscribeSnapshots(context.actorOf(Props(new Actor {
    protected def receive = {
      case snapshot: Snapshot[SessContentsRecord] =>
        log.info("SessContentsRecord SIZE = "+snapshot.data.size)
        snapshot.data foreach {
          rec =>
            log.info("SessContentsRecord: " + rec)
        }
    }
  })))


  protected def receive = {
    case GetOngoingSession => sender ! OngoingSession(ongoingSession)

    case Snapshot(repo, data: Iterable[SessionRecord]) if (repo == sessionRepository) =>
      updateSessions(data)

    case snapshot@Snapshot(repo, _) if (repo == futSessContentsRepository) =>
      handleFutSessContents(snapshot.asInstanceOf[Snapshot[SessContentsRecord]])
  }

  protected def handleFutSessContents(snapshot: Snapshot[FutInfo.SessContentsRecord]) {
    trackingSessions.foreach {case (id, session)=>
      session ! FutInfoSessionContents(snapshot.filter(_.sessId == id))
    }
  }

  protected def updateSessions(records: Iterable[SessionRecord]) {
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