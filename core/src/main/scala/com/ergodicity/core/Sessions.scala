package com.ergodicity.core

import com.ergodicity.plaza2.scheme.FutInfo._
import com.ergodicity.plaza2.DataStream.BindTable
import com.ergodicity.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import session.Session.{OptInfoSessionContents, FutInfoSessionContents}
import session.{Session, SessionState, IntClearingState, SessionContent}
import com.ergodicity.plaza2.scheme.{OptInfo, Deserializer, FutInfo}
import com.ergodicity.plaza2.{DataStreamState, Repository}
import akka.actor._
import scalaz._
import Scalaz._
import akka.actor.FSM.{UnsubscribeTransitionCallBack, Transition, CurrentState, SubscribeTransitionCallBack}


protected[core] case class SessionId(id: Long, optionSessionId: Long)

object Sessions {
  def apply(FutInfoStream: ActorRef, OptInfoStream: ActorRef) = new Sessions(FutInfoStream, OptInfoStream)

  case object GetOngoingSession

  case class OngoingSession(session: Option[ActorRef])

  case object BindSessions
}

sealed trait SessionsState

object SessionsState {

  case object Idle extends SessionsState

  case object Binded extends SessionsState

  case object LoadingSessions extends SessionsState

  case object LoadingFuturesContents extends SessionsState

  case object LoadingOptionsContents extends SessionsState

  case object Online extends SessionsState

}

sealed trait SessionsData

object SessionsData {

  case object Blank extends SessionsData

  case class BindingStates(futures: Option[DataStreamState], options: Option[DataStreamState]) extends SessionsData

  case class TrackingSessions(sessions: Map[SessionId, ActorRef], ongoing: Option[ActorRef]) extends SessionsData {
    def updateWith(records: Iterable[SessionRecord])(implicit context: ActorContext): TrackingSessions = {
      {
        val (alive, outdated) = sessions.partition {
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

        TrackingSessions(alive ++ newSessions, records.filter {
          record => SessionState(record.state) match {
            case SessionState.Completed | SessionState.Canceled => false
            case _ => true
          }
        }.headOption.flatMap {
          record => (alive ++ newSessions).get(SessionId(record.sessionId, record.optionsSessionId))
        })
      }
    }
  }

}

class Sessions(FutInfoStream: ActorRef, OptInfoStream: ActorRef) extends Actor with FSM[SessionsState, SessionsData] {

  import Sessions._
  import SessionsState._
  import SessionsData._

  // Repositories
  val SessionRepository = context.actorOf(Props(Repository[SessionRecord]), "SessionRepository")
  val FutSessContentsRepository = context.actorOf(Props(Repository[FutInfo.SessContentsRecord]), "FutSessContentsRepository")
  val OptSessContentsRepository = context.actorOf(Props(Repository[OptInfo.SessContentsRecord]), "OptSessContentsRepository")

  startWith(Idle, Blank)

  when(Idle) {
    case Event(BindSessions, Blank) => goto(Binded) using BindingStates(None, None)
  }

  when(Binded) {
    // Handle FutInfo and OptInfo data streams state updates
    case Event(CurrentState(FutInfoStream, state: DataStreamState), binding: BindingStates) =>
      handleBindingState(binding.copy(futures = Some(state)))

    case Event(CurrentState(OptInfoStream, state: DataStreamState), binding: BindingStates) =>
      handleBindingState(binding.copy(options = Some(state)))

    case Event(Transition(FutInfoStream, _, state: DataStreamState), binding: BindingStates) =>
      handleBindingState(binding.copy(futures = Some(state)))

    case Event(Transition(OptInfoStream, _, state: DataStreamState), binding: BindingStates) =>
      handleBindingState(binding.copy(options = Some(state)))
  }

  when(LoadingSessions) {
    case Event(Snapshot(SessionRepository, data: Iterable[SessionRecord]), tracking: TrackingSessions) =>
      goto(LoadingFuturesContents) using tracking.updateWith(data)
  }

  when(LoadingFuturesContents) {
    case Event(snapshot@Snapshot(FutSessContentsRepository, _), tracking: TrackingSessions) =>
      dispatchFutSessContents(snapshot.asInstanceOf[Snapshot[FutInfo.SessContentsRecord]])(tracking)
      goto(LoadingOptionsContents)
  }

  when(LoadingOptionsContents) {
    case Event(snapshot@Snapshot(OptSessContentsRepository, _), tracking: TrackingSessions) =>
      dispatchOptSessContents(snapshot.asInstanceOf[Snapshot[OptInfo.SessContentsRecord]])(tracking)
      goto(Online)
  }

  when(Online) {
    case Event(GetOngoingSession, TrackingSessions(_, ongoing)) =>
      sender ! OngoingSession(ongoing);
      stay()

    case Event(snapshot@Snapshot(FutSessContentsRepository, _), tracking: TrackingSessions) =>
      dispatchFutSessContents(snapshot.asInstanceOf[Snapshot[FutInfo.SessContentsRecord]])(tracking)
      stay()

    case Event(snapshot@Snapshot(OptSessContentsRepository, _), tracking: TrackingSessions) =>
      dispatchOptSessContents(snapshot.asInstanceOf[Snapshot[OptInfo.SessContentsRecord]])(tracking)
      stay()
  }

  whenUnhandled {
    case Event(Snapshot(SessionRepository, data: Iterable[SessionRecord]), tracking: TrackingSessions) =>
      stay() using tracking.updateWith(data)
  }

  onTransition {
    case Idle -> Binded =>
      log.debug("Bind to FutInfo and OptInfo data streams")

      // Bind to tables
      FutInfoStream ! BindTable("session", SessionRepository, implicitly[Deserializer[SessionRecord]])
      FutInfoStream ! BindTable("fut_sess_contents", FutSessContentsRepository, implicitly[Deserializer[FutInfo.SessContentsRecord]])
      OptInfoStream ! BindTable("opt_sess_contents", OptSessContentsRepository, implicitly[Deserializer[OptInfo.SessContentsRecord]])

      // Track Data Stream states
      FutInfoStream ! SubscribeTransitionCallBack(self)
      OptInfoStream ! SubscribeTransitionCallBack(self)

    case Binded -> LoadingSessions =>
      log.debug("Loading sessions")
      // Unsubscribe from updates
      FutInfoStream ! UnsubscribeTransitionCallBack(self)
      OptInfoStream ! UnsubscribeTransitionCallBack(self)
      // Subscribe for sessions snapshots
      SessionRepository ! SubscribeSnapshots(self)

    case LoadingSessions -> LoadingFuturesContents =>
      log.debug("Sessions loaded; Load Futures contents")
      FutSessContentsRepository ! SubscribeSnapshots(self)

    case LoadingFuturesContents -> LoadingOptionsContents =>
      log.debug("Futures contents loaded; Load Options contents")
      OptSessContentsRepository ! SubscribeSnapshots(self)

    case LoadingOptionsContents -> Online =>
      log.debug("Sessions contentes loaded")
  }

  protected def dispatchOptSessContents(snapshot: Snapshot[OptInfo.SessContentsRecord])(implicit tracking: TrackingSessions) {
    tracking.sessions.foreach {
      case (SessionId(_, id), session) =>
        session ! OptInfoSessionContents(snapshot.filter(_.sessionId == id))
    }
  }

  protected def dispatchFutSessContents(snapshot: Snapshot[FutInfo.SessContentsRecord])(implicit tracking: TrackingSessions) {
    tracking.sessions.foreach {
      case (SessionId(id, _), session) =>
        session ! FutInfoSessionContents(snapshot.filter(_.sessionId == id))
    }
  }

  protected def handleBindingState(state: BindingStates): State = (state.futures <**> state.options) {(_, _)} match {
    case Some((DataStreamState.Online, DataStreamState.Online)) => goto(LoadingSessions) using TrackingSessions(Map(), None)
    case _ => stay() using state
  }
}