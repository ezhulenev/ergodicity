package com.ergodicity.core

import akka.actor._
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}
import com.ergodicity.cgate.{Reads, WhenUnhandled, SysEvent}
import akka.util.duration._
import collection.mutable
import session.Instrument.Limits
import session._
import akka.util
import com.ergodicity.cgate.StreamEvent.{TnCommit, TnBegin}
import com.ergodicity.core.SessionsTracking._
import com.ergodicity.core.SessionsTracking.FutSysEvent
import scala.Some
import com.ergodicity.core.SessionsTracking.OptSessContents
import com.ergodicity.core.SessionsTracking.FutSessContents
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.core.SessionsTracking.SessionEvent
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.StreamEvent.ClearDeleted
import com.ergodicity.core.SessionsTrackingState.{TrackingSessions, Synchronizing}
import com.ergodicity.cgate.SysEvent.SessionDataReady
import com.ergodicity.core.session.Implicits._

case class SessionId(id: Int, optionSessionId: Int)

object SessionsTracking {
  def apply(FutInfoStream: ActorRef, OptInfoStream: ActorRef) = new SessionsTracking(FutInfoStream, OptInfoStream)

  case class SubscribeOngoingSessions(ref: ActorRef)

  case class OngoingSession(session: Option[(SessionId, ActorRef)])

  case class OngoingSessionTransition(from: Option[(SessionId, ActorRef)], to: Option[(SessionId, ActorRef)])

  // Dispatching events
  case class DropSession(id: SessionId)

  case class SessionEvent(id: SessionId, session: Session, state: SessionState, intradayClearingState: IntradayClearingState)

  case class FutSessContents(sessionId: Int, instrument: Instrument, state: InstrumentState)

  case class OptSessContents(sessionId: Int, instrument: Instrument)

  case class FutSysEvent(event: SysEvent)

  case class OptSysEvent(event: SysEvent)

  case class PendingEvents(sess: Seq[SessionEvent] = Seq(), fut: Seq[FutSessContents] = Seq(), opt: Seq[OptSessContents] = Seq()) {
    def append(e: SessionEvent) = copy(sess = sess :+ e)

    def append(e: FutSessContents) = copy(fut = fut :+ e)

    def append(e: OptSessContents) = copy(opt = opt :+ e)

    def remove(id: SessionId): PendingEvents =
      copy(sess.filterNot(_.id == id), fut.filterNot(_.sessionId == id.id), opt.filterNot(_.sessionId == id.optionSessionId))

    def consume(id: SessionId): (Seq[SessionEvent], Seq[FutSessContents], Seq[OptSessContents]) =
      (sess.filter(_.id == id), fut.filter(_.sessionId == id.id), opt.filter(_.sessionId == id.optionSessionId))
  }

  case class SystemEvents(fut: Seq[FutSysEvent] = Seq(), opt: Seq[OptSysEvent] = Seq()) {
    def append(e: FutSysEvent) = copy(fut = fut :+ e)

    def append(e: OptSysEvent) = copy(opt = opt :+ e)

    def remove(eventId: Long) = copy(fut.filterNot(_.event.eventId == eventId), opt.filterNot(_.event.eventId == eventId))

    def synchronized: Option[(FutSysEvent, OptSysEvent)] = {
      (fut.map(_.event.eventId) intersect opt.map(_.event.eventId)).headOption.flatMap {
        case eventId =>
          (fut.find(_.event.eventId == eventId) zip opt.find(_.event.eventId == eventId)).headOption
      }
    }
  }

  case class SynchronizeOnEvent(fut: SysEvent, opt: SysEvent) {
    if (fut.eventId != opt.eventId) {
      throw new IllegalArgumentException("Event id should be the same")
    }

    def eventId = fut.eventId
  }

}

sealed trait SessionsTrackingState

object SessionsTrackingState {

  case object TrackingSessions extends SessionsTrackingState

  case object Synchronizing extends SessionsTrackingState

}

class SessionsTracking(FutInfoStream: ActorRef, OptInfoStream: ActorRef) extends Actor with FSM[SessionsTrackingState, (SystemEvents, PendingEvents)] {

  import SessionsTracking._

  implicit val timeout = util.Timeout(1.second)

  var subscribers: List[ActorRef] = Nil

  var ongoingSession: Option[(SessionId, ActorRef)] = None

  val sessions = mutable.Map[SessionId, ActorRef]()

  // Dispatch events from FutInfo & OptInfo streams
  val futuresDispatcher = context.actorOf(Props(new FutInfoDispatcher(self, FutInfoStream)), "FutInfoDispatcher")
  val optionsDispatcher = context.actorOf(Props(new OptInfoDispatcher(self, OptInfoStream)), "OptInfoDispatcher")

  override def preStart() {
    log.info("Start session tracking")

  }

  when(TrackingSessions) {
    case Event("NoSuchEventEver", _) => stay()
  }

  when(Synchronizing) {
    case Event(sync@SynchronizeOnEvent(SessionDataReady(_, futSessionId), SessionDataReady(_, optSessionId)), (sysEvents, pending)) =>
      val sessionId = SessionId(futSessionId, optSessionId)
      val (sessionEvents, futContents, optContents) = pending.consume(SessionId(futSessionId, optSessionId))

      for (sessionEvent <- sessionEvents) {
        val sessionActor = sessions.getOrElseUpdate(sessionId, context.actorOf(Props(new SessionActor(sessionEvent.session)), sessionId.toString))
        sessionActor ! sessionEvent.state
        sessionActor ! sessionEvent.intradayClearingState
      }

      for (futContent <- futContents) {
        sessions(sessionId) ! futContent
      }

      for (optContent <- optContents) {
        sessions(sessionId) ! optContent
      }

      changeOngoingSession(sessionId)

      goto(TrackingSessions) using(sysEvents.remove(sync.eventId), pending.remove(sessionId))
  }

  whenUnhandled {
    case Event(SubscribeOngoingSessions(ref), _) =>
      subscribers = ref +: subscribers
      ref ! OngoingSession(ongoingSession)
      stay()

    // Dispatch Sessions
    case Event(e@SessionEvent(id, _, _, _), (sysEvents, pendingEvents: PendingEvents)) if (!sessions.contains(id)) =>
      stay() using(sysEvents, pendingEvents append e)

    case Event(e@SessionEvent(id, _, state, intState), _) if (sessions contains id) =>
      sessions(id) ! state
      sessions(id) ! intState
      stay()

    // Dispatch Futures contents
    case Event(e@FutSessContents(id, _, _), (sysEvents, pending: PendingEvents)) if (!sessions.exists(_._1.id == id)) =>
      stay() using(sysEvents, pending append e)

    case Event(e@FutSessContents(id, _, state), _) if (sessions.exists(_._1.id == id)) =>
      sessions.find(_._1.id == id) foreach (_._2 ! e)
      stay()

    // Dispatch Option contents
    case Event(e@OptSessContents(id, _), (sysEvents, pending: PendingEvents)) if (!sessions.exists(_._1.optionSessionId == id)) =>
      stay() using(sysEvents, pending append e)

    case Event(e@OptSessContents(id, _), _) if (sessions.exists(_._1.optionSessionId == id)) =>
      sessions.find(_._1.optionSessionId == id) foreach (_._2 ! e)
      stay()

    // Dispatch sys events
    case Event(e: FutSysEvent, (sysEvents, pending)) =>
      synchronize(sysEvents.append(e), pending)

    case Event(e: OptSysEvent, (sysEvents, pending)) =>
      synchronize(sysEvents.append(e), pending)

    // Drop session
    case Event(DropSession(id), _) =>
      sessions(id) ! PoisonPill
      sessions.remove(id)
      stay()
  }

  private def synchronize(sysEvents: SystemEvents, pending: PendingEvents): State = sysEvents.synchronized.map {
    case (futEvent, optEvent) =>
      self ! SynchronizeOnEvent(futEvent.event, optEvent.event)
      goto(Synchronizing) using(sysEvents, pending)
  } getOrElse (stay() using(sysEvents, pending))

  private def changeOngoingSession(id: SessionId) {
    val newOngoingSession = Some((id, sessions(id)))
    subscribers.foreach(_ ! OngoingSessionTransition(ongoingSession, newOngoingSession))
    ongoingSession = newOngoingSession
  }
}


class FutInfoDispatcher(sessionsTracking: ActorRef, stream: ActorRef) extends Actor with ActorLogging with WhenUnhandled {

  import com.ergodicity.cgate.Protocol._

  override def preStart() {
    stream ! SubscribeStreamEvents(self)
  }

  protected def receive = handleEvent orElse whenUnhandled

  private def handleEvent: Receive = {
    case TnBegin =>
    case TnCommit =>
    case _: ClearDeleted =>

    case StreamData(FutInfo.session.TABLE_INDEX, data) =>
      val record = implicitly[Reads[FutInfo.session]] apply data
      if (record.get_replAct() == 0) {
        val id = SessionId(record.get_sess_id(), record.get_opt_sess_id())
        sessionsTracking ! SessionEvent(id, Session from record, SessionState(record.get_state()), IntradayClearingState(record.get_inter_cl_state()))
      }

    case StreamData(FutInfo.fut_sess_contents.TABLE_INDEX, data) =>
      val record = implicitly[Reads[FutInfo.fut_sess_contents]] apply data
      // Handle only Futures, ignore Repo etc.
      if (record.get_replAct() == 0 && record.isFuture) {
        val security = implicitly[ToSecurity[FutInfo.fut_sess_contents]] convert record
        val instrument = Instrument(security, Limits(record.get_limit_down(), record.get_limit_up()))
        sessionsTracking ! FutSessContents(record.get_sess_id(), instrument, InstrumentState(record.get_state()))
      }

    case StreamData(FutInfo.sys_events.TABLE_INDEX, data) =>
      val record = implicitly[Reads[FutInfo.sys_events]] apply data
      if (record.get_replAct() == 0) {
        sessionsTracking ! FutSysEvent(SysEvent(record))
      }
  }
}

class OptInfoDispatcher(sessionsTracking: ActorRef, stream: ActorRef) extends Actor with ActorLogging with WhenUnhandled {

  import com.ergodicity.cgate.Protocol._

  override def preStart() {
    stream ! SubscribeStreamEvents(self)
  }

  protected def receive = handleEvent orElse whenUnhandled

  private def handleEvent: Receive = {
    case TnBegin =>
    case TnCommit =>
    case _: ClearDeleted =>

    case StreamData(OptInfo.opt_sess_contents.TABLE_INDEX, data) =>
      val record = implicitly[Reads[OptInfo.opt_sess_contents]] apply data
      if (record.get_replAct() == 0) {
        val security = implicitly[ToSecurity[OptInfo.opt_sess_contents]] convert record
        val instrument = Instrument(security, Limits(record.get_limit_down(), record.get_limit_up()))
        sessionsTracking ! OptSessContents(record.get_sess_id(), instrument)
      }

    case StreamData(OptInfo.sys_events.TABLE_INDEX, data) =>
      val record = implicitly[Reads[OptInfo.sys_events]] apply data
      if (record.get_replAct() == 0) {
        sessionsTracking ! FutSysEvent(SysEvent(record))
      }
  }
}

