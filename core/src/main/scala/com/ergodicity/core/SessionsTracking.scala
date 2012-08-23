package com.ergodicity.core

import akka.actor._
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}
import com.ergodicity.cgate.sysevents.{SysEventDispatcher, SysEvent}
import com.ergodicity.cgate.DataStreamState
import akka.util.Timeout
import akka.util.duration._
import collection.mutable
import com.ergodicity.cgate.repository.Repository
import com.ergodicity.cgate.DataStream.BindingResult
import akka.dispatch.Await
import akka.pattern.ask
import akka.pattern.pipe
import com.ergodicity.cgate.repository.Repository.GetSnapshot
import session.Session.{FutInfoSessionContents, OptInfoSessionContents}
import session.{Session, SessionContent, IntradayClearingState, SessionState}
import com.ergodicity.cgate.sysevents.SysEventDispatcher.SubscribeSysEvents
import com.ergodicity.cgate.repository.Repository.SubscribeSnapshots
import akka.actor.FSM.Transition
import akka.actor.FSM.UnsubscribeTransitionCallBack
import scala.Some
import com.ergodicity.cgate.DataStream.BindingSucceed
import com.ergodicity.cgate.repository.Repository.Snapshot
import com.ergodicity.cgate.sysevents.SysEvent.SessionDataReady
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.cgate.DataStream.BindTable
import scalaz._
import Scalaz._

case class SessionId(id: Long, optionSessionId: Long)

object SessionsTracking {
  def apply(FutInfoStream: ActorRef, OptInfoStream: ActorRef) = new SessionsTracking(FutInfoStream, OptInfoStream)

  case class SubscribeOngoingSessions(ref: ActorRef)

  case class OngoingSession(session: Option[(SessionId, ActorRef)])

  case class OngoingSessionTransition(from: Option[(SessionId, ActorRef)], to: Option[(SessionId, ActorRef)])

  case class UpdateOngoingSessions(snapshot: Snapshot[FutInfo.session])

  private[SessionsTracking] case class StreamSysEvent(source: ActorRef, event: SysEvent)

  private[SessionsTracking] class StreamSysEvents(stream: ActorRef) extends Actor {
    protected def receive = {
      case event: SysEvent =>
        context.parent ! StreamSysEvent(stream, event)
    }
  }

  case class StreamStates(futures: Option[DataStreamState] = None, options: Option[DataStreamState] = None)

}

sealed trait SessionsTrackingState

object SessionsTrackingState {

  case object Binded extends SessionsTrackingState

  case object Online extends SessionsTrackingState

}

class SessionsTracking(FutInfoStream: ActorRef, OptInfoStream: ActorRef) extends Actor with LoggingFSM[SessionsTrackingState, SessionsTracking.StreamStates] {

  import SessionsTracking._
  import SessionsTrackingState._

  implicit val timeout = Timeout(1.second)

  // Subscribers for ongoing sessions
  var subscribers: List[ActorRef] = Nil

  var ongoingSession: Option[(SessionId, ActorRef)] = None

  val trackingSessions = mutable.Map[SessionId, ActorRef]()

  // Repositories
  import com.ergodicity.cgate.Protocol._
  val SessionRepository = context.actorOf(Props(Repository[FutInfo.session]), "SessionRepository")
  val FutSessContentsRepository = context.actorOf(Props(Repository[FutInfo.fut_sess_contents]), "FutSessContentsRepository")
  val OptSessContentsRepository = context.actorOf(Props(Repository[OptInfo.opt_sess_contents]), "OptSessContentsRepository")

  // SysEventsDispatcher
  val sysEventsDispatcher = context.actorOf(Props(new SysEventDispatcher[FutInfo.sys_events]), "FutInfoSysEventsDispatcher")
  sysEventsDispatcher ! SubscribeSysEvents(context.actorOf(Props(new StreamSysEvents(FutInfoStream)), "FutInfoEventsWrapper"))
  FutInfoStream ? BindTable(FutInfo.sys_events.TABLE_INDEX, sysEventsDispatcher)

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

  override def preStart() {
    log.debug("Subscribe for FutInfo and OptInfo states")
    FutInfoStream ! SubscribeTransitionCallBack(self)
    OptInfoStream ! SubscribeTransitionCallBack(self)

    // Subscribe for snapshots data
    SessionRepository ! SubscribeSnapshots(self)
    FutSessContentsRepository ! SubscribeSnapshots(self)
    OptSessContentsRepository ! SubscribeSnapshots(self)
  }

  startWith(Binded, StreamStates(None, None))

  when(Binded) {
    // Handle FutInfo and OptInfo data streams state updates
    case Event(CurrentState(FutInfoStream, state: DataStreamState), states) =>
      handleBindingState(states.copy(futures = Some(state)))

    case Event(CurrentState(OptInfoStream, state: DataStreamState), states) =>
      handleBindingState(states.copy(options = Some(state)))

    case Event(Transition(FutInfoStream, _, state: DataStreamState), states) =>
      handleBindingState(states.copy(futures = Some(state)))

    case Event(Transition(OptInfoStream, _, state: DataStreamState), states) =>
      handleBindingState(states.copy(options = Some(state)))
  }

  when(Online) {
    case Event(t@Transition(OptInfoStream, _, _), _) =>
      throw new RuntimeException("Unexpected OptInfo data stream transtition = ")

    case Event(t@Transition(FutInfoStream, _, _), _) =>
      throw new RuntimeException("Unexpected FutInfo data stream transtition = ")
  }

  whenUnhandled {
    case Event(SubscribeOngoingSessions(ref), _) =>
      subscribers = ref +: subscribers
      ref ! OngoingSession(ongoingSession)
      stay()

    case Event(StreamSysEvent(FutInfoStream, SessionDataReady(id)), _) if (!trackingSessions.exists(_._1.id == id)) =>
      log.info("Session data ready; Id = " + id)
      (SessionRepository ? GetSnapshot).mapTo[Snapshot[FutInfo.session]].map(s => UpdateOngoingSessions(s.filter(_.get_sess_id() <= id))) pipeTo self
      stay()

    case Event(UpdateOngoingSessions(snapshot), _) =>
      val newOngoingSession = updateOngoingSessions(snapshot)
      log.debug("New ongoing session = " + newOngoingSession + ", previously was = " + ongoingSession)
      if (newOngoingSession != ongoingSession) {
        subscribers.foreach(_ ! OngoingSessionTransition(ongoingSession, newOngoingSession))
        ongoingSession = newOngoingSession
      }
      // Request snapshots for loading new contents
      FutSessContentsRepository ! GetSnapshot
      OptSessContentsRepository ! GetSnapshot
      stay()

    case Event(snapshot@Snapshot(SessionRepository, data), _) =>
      dispatchSessions(snapshot.asInstanceOf[Snapshot[FutInfo.session]])
      stay()

    case Event(snapshot@Snapshot(FutSessContentsRepository, _), _) =>
      dispatchFuturesContents(snapshot.asInstanceOf[Snapshot[FutInfo.fut_sess_contents]])
      stay()

    case Event(snapshot@Snapshot(OptSessContentsRepository, _), _) =>
      dispatchOptionsContents(snapshot.asInstanceOf[Snapshot[OptInfo.opt_sess_contents]])
      stay()
  }

  onTransition {
    case Binded -> Online =>
      log.debug("Sessions goes online")
      FutInfoStream ! UnsubscribeTransitionCallBack(self)
      OptInfoStream ! UnsubscribeTransitionCallBack(self)
  }


  protected def updateOngoingSessions(snapshot: Snapshot[FutInfo.session]): Option[(SessionId, ActorRef)] = {
    val records = snapshot.data
    log.info("Update ongoing sessions based on record = " + records)

    // Split sessions on still alive and dropped
    val (alive, dropped) = trackingSessions.partition {
      case (SessionId(i1, i2), ref) => records.exists(r => r.get_sess_id() == i1 && r.get_opt_sess_id() == i2)
    }

    // Kill all dropped sessions
    dropped.foreach {
      case (id, session) =>
        session ! PoisonPill
        trackingSessions.remove(id)
    }

    // Create actors for new sessions
    records.filter(record => !alive.contains(SessionId(record.get_sess_id(), record.get_opt_sess_id()))).map {
      newRecord =>
        val sessionId = newRecord.get_sess_id()
        val state = SessionState(newRecord.get_state())
        val intClearingState = IntradayClearingState(newRecord.get_inter_cl_state())
        val content = new SessionContent(newRecord)
        val session = context.actorOf(Props(new Session(content, state, intClearingState)), sessionId.toString)

        trackingSessions(SessionId(sessionId, newRecord.get_opt_sess_id())) = session
    }

    // Select ongoing session
    records.toList.sortBy(_.get_sess_id()).filter(session => SessionState(session.get_state()) match {
      case SessionState.Assigned | SessionState.Online | SessionState.Suspended => true
      case _ => false
    }).map(s => SessionId(s.get_sess_id(), s.get_opt_sess_id()))
      .map(id => trackingSessions.get(id) map ((id, _)))
      .collect({
      case Some(x) => x
    })
      .headOption
  }

  protected def dispatchSessions(snapshot: Snapshot[FutInfo.session]) {
    trackingSessions.foreach {
      case (SessionId(i1, i2), session) =>
        snapshot.data.find(s => s.get_sess_id() == i1 && s.get_opt_sess_id() == i2) foreach {
          record =>
            session ! SessionState(record.get_state())
            session ! IntradayClearingState(record.get_inter_cl_state())
        }
    }
  }

  protected def dispatchOptionsContents(snapshot: Snapshot[OptInfo.opt_sess_contents]) {
    log.debug("Dispatch Options contents, size = " + snapshot.data.size)
    trackingSessions.foreach {
      case (SessionId(_, id), session) =>
        session ! OptInfoSessionContents(snapshot.filter(_.get_sess_id() == id))
    }
  }

  protected def dispatchFuturesContents(snapshot: Snapshot[FutInfo.fut_sess_contents]) {
    log.debug("Dispatch Futures contents, size = " + snapshot.data.size)
    trackingSessions.foreach {
      case (SessionId(id, _), session) =>
        session ! FutInfoSessionContents(snapshot.filter(_.get_sess_id() == id))
    }
  }

  protected def handleBindingState(state: StreamStates): State = {
    (state.futures <**> state.options)((_, _)) match {
      case Some((DataStreamState.Online, DataStreamState.Online)) => goto(Online) using state
      case _ => stay() using state
    }
  }
}
