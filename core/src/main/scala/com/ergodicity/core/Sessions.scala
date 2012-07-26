package com.ergodicity.core

import session.Session.{OptInfoSessionContents, FutInfoSessionContents}
import session.{Session, SessionState, IntClearingState, SessionContent}
import akka.actor._
import akka.util.duration._
import akka.pattern.ask
import scalaz._
import Scalaz._
import akka.actor.FSM.{UnsubscribeTransitionCallBack, Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.cgate.DataStreamState
import com.ergodicity.cgate.repository.Repository
import com.ergodicity.cgate.repository.ReplicaExtractor._
import com.ergodicity.cgate.Protocol._
import com.ergodicity.cgate.DataStream._
import com.ergodicity.cgate.repository.Repository.{SubscribeSnapshots, Snapshot}
import com.ergodicity.cgate.scheme.{FutInfo, OptInfo}
import akka.dispatch.Await
import akka.util.Timeout


protected[core] case class SessionId(id: Long, optionSessionId: Long)

object Sessions {
  def apply(FutInfoStream: ActorRef, OptInfoStream: ActorRef) = new Sessions(FutInfoStream, OptInfoStream)

  // Tracking ongoing sessions

  case class SubscribeOngoingSessions(ref: ActorRef)

  case class CurrentOngoingSession(session: Option[ActorRef])

  case class OngoingSessionTransition(session: Option[ActorRef])
}

sealed trait SessionsState

object SessionsState {

  case object Binded extends SessionsState

  case object LoadingSessions extends SessionsState

  case object LoadingFuturesContents extends SessionsState

  case object LoadingOptionsContents extends SessionsState

  case object Online extends SessionsState

}

sealed trait SessionsData

object SessionsData {

  case object Blank extends SessionsData

  case class StreamState(futures: Option[DataStreamState], options: Option[DataStreamState]) extends SessionsData

  case class TrackingSessions(sessions: Map[SessionId, ActorRef], ongoing: Option[ActorRef]) extends SessionsData {
    def updateWith(records: Iterable[FutInfo.session])(implicit context: ActorContext): TrackingSessions = {
      {
        val (alive, outdated) = sessions.partition {
          case (SessionId(i1, i2), ref) => records.find((r: FutInfo.session) => r.get_sess_id() == i1 && r.get_opt_sess_id() == i2).isDefined
        }

        // Kill all outdated sessions
        outdated.foreach {
          case (id, session) => session ! PoisonPill
        }

        // Update status for still alive sessions
        alive.foreach {
          case (SessionId(i1, i2), session) =>
            records.find((r: FutInfo.session) => r.get_sess_id() == i1 && r.get_opt_sess_id() == i2) foreach {
              record =>
                session ! SessionState(record.get_state())
                session ! IntClearingState(record.get_inter_cl_state())
            }
        }

        // Create actors for new sessions
        val newSessions = records.filter(record => !alive.contains(SessionId(record.get_sess_id(), record.get_opt_sess_id()))).map {
          newRecord =>
            val sessionId = newRecord.get_sess_id()
            val state = SessionState(newRecord.get_state())
            val intClearingState = IntClearingState(newRecord.get_inter_cl_state())
            val content = new SessionContent(newRecord)
            val session = context.actorOf(Props(new Session(content, state, intClearingState)), sessionId.toString)

            SessionId(sessionId, newRecord.get_opt_sess_id()) -> session
        }

        TrackingSessions(alive ++ newSessions, records.filter {
          record => SessionState(record.get_state()) match {
            case SessionState.Completed | SessionState.Canceled => false
            case _ => true
          }
        }.headOption.flatMap {
          record => (alive ++ newSessions).get(SessionId(record.get_sess_id(), record.get_opt_sess_id()))
        })
      }
    }
  }

}

class Sessions(FutInfoStream: ActorRef, OptInfoStream: ActorRef) extends Actor with FSM[SessionsState, SessionsData] {

  import Sessions._
  import SessionsState._
  import SessionsData._

  implicit val timeout = Timeout(1.second)
  
  // Subscribers for ongoing sessions
  var subscribers: List[ActorRef] = Nil
  
  // Repositories
  val SessionRepository = context.actorOf(Props(Repository[FutInfo.session]), "SessionRepository")
  val FutSessContentsRepository = context.actorOf(Props(Repository[FutInfo.fut_sess_contents]), "FutSessContentsRepository")
  val OptSessContentsRepository = context.actorOf(Props(Repository[OptInfo.opt_sess_contents]), "OptSessContentsRepository")

  log.debug("Bind to FutInfo and OptInfo data streams")

  // Bind to tables
  val sessionsBindingResult = (FutInfoStream ? BindTable(FutInfo.session.TABLE_INDEX, SessionRepository)).mapTo[BindingResult]
  val futuresBindingResult = (FutInfoStream ? BindTable(FutInfo.fut_sess_contents.TABLE_INDEX, FutSessContentsRepository)).mapTo[BindingResult]
  val optionsBindingResult = (OptInfoStream ? BindTable(OptInfo.opt_sess_contents.TABLE_INDEX, OptSessContentsRepository)).mapTo[BindingResult]

  val bindingResult = for {
    sess <- sessionsBindingResult
    fut <- futuresBindingResult
    opt <- optionsBindingResult
  } yield (sess, fut, opt)

  Await.result(bindingResult, 1.second) match {
    case (BindingSucceed(_, _), BindingSucceed(_, _), BindingSucceed(_, _)) =>
    case _ => throw new IllegalStateException("Failed Bind to data streams")
  }

  // Track Data Stream states
  FutInfoStream ! SubscribeTransitionCallBack(self)
  OptInfoStream ! SubscribeTransitionCallBack(self)

  startWith(Binded, StreamState(None, None))

  when(Binded) {
    // Handle FutInfo and OptInfo data streams state updates
    case Event(CurrentState(FutInfoStream, state: DataStreamState), states: StreamState) =>
      handleBindingState(states.copy(futures = Some(state)))

    case Event(CurrentState(OptInfoStream, state: DataStreamState), states: StreamState) =>
      handleBindingState(states.copy(options = Some(state)))

    case Event(Transition(FutInfoStream, _, state: DataStreamState), states: StreamState) =>
      handleBindingState(states.copy(futures = Some(state)))

    case Event(Transition(OptInfoStream, _, state: DataStreamState), states: StreamState) =>
      handleBindingState(states.copy(options = Some(state)))
  }

  when(LoadingSessions) {
    case Event(Snapshot(SessionRepository, data), tracking: TrackingSessions) =>
      goto(LoadingFuturesContents) using tracking.updateWith(data.asInstanceOf[Iterable[FutInfo.session]])
  }

  when(LoadingFuturesContents) {
    case Event(snapshot@Snapshot(FutSessContentsRepository, _), tracking: TrackingSessions) =>
      dispatchFutSessContents(snapshot.asInstanceOf[Snapshot[FutInfo.fut_sess_contents]])(tracking)
      goto(LoadingOptionsContents)
  }

  when(LoadingOptionsContents) {
    case Event(snapshot@Snapshot(OptSessContentsRepository, _), tracking: TrackingSessions) =>
      dispatchOptSessContents(snapshot.asInstanceOf[Snapshot[OptInfo.opt_sess_contents]])(tracking)
      goto(Online)
  }

  when(Online) {
    case Event(SubscribeOngoingSessions(ref), TrackingSessions(_, ongoing)) =>
      subscribers = ref +: subscribers
      ref ! CurrentOngoingSession(ongoing)
      stay()

    case Event(Snapshot(SessionRepository, data), tracking: TrackingSessions) =>
      val updated = tracking.updateWith(data.asInstanceOf[Iterable[FutInfo.session]])
      if (updated.ongoing != tracking.ongoing) {
        subscribers.foreach(_ ! OngoingSessionTransition(updated.ongoing))
      }
      stay() using updated

    case Event(snapshot@Snapshot(FutSessContentsRepository, _), tracking: TrackingSessions) =>
      dispatchFutSessContents(snapshot.asInstanceOf[Snapshot[FutInfo.fut_sess_contents]])(tracking)
      stay()

    case Event(snapshot@Snapshot(OptSessContentsRepository, _), tracking: TrackingSessions) =>
      dispatchOptSessContents(snapshot.asInstanceOf[Snapshot[OptInfo.opt_sess_contents]])(tracking)
      stay()
  }

  whenUnhandled {
    case Event(Snapshot(SessionRepository, data), tracking: TrackingSessions) =>
      stay() using tracking.updateWith(data.asInstanceOf[Iterable[FutInfo.session]])
  }

  onTransition {
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

    case t => log.warning("Unexpected transition = " + t)
  }

  protected def dispatchOptSessContents(snapshot: Snapshot[OptInfo.opt_sess_contents])(implicit tracking: TrackingSessions) {
    tracking.sessions.foreach {
      case (SessionId(_, id), session) =>
        session ! OptInfoSessionContents(snapshot.filter(_.get_sess_id() == id))
    }
  }

  protected def dispatchFutSessContents(snapshot: Snapshot[FutInfo.fut_sess_contents])(implicit tracking: TrackingSessions) {
    tracking.sessions.foreach {
      case (SessionId(id, _), session) =>
        session ! FutInfoSessionContents(snapshot.filter(_.get_sess_id() == id))
    }
  }

  protected def handleBindingState(state: StreamState): State = {
    (state.futures <**> state.options) {(_, _)} match {
      case Some((DataStreamState.Online, DataStreamState.Online)) => goto(LoadingSessions) using TrackingSessions(Map(), None)
      case _ => stay() using state
    }
  }
}