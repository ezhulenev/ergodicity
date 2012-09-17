package com.ergodicity.core

import akka.actor._
import akka.util
import akka.util.duration._
import collection.mutable
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.StreamEvent.ClearDeleted
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.StreamEvent.{TnCommit, TnBegin}
import com.ergodicity.cgate.SysEvent.SessionDataReady
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}
import com.ergodicity.cgate.{Reads, WhenUnhandled, SysEvent}
import com.ergodicity.core.SessionsTracking._
import com.ergodicity.core.session.Implicits._
import scala.Some
import session._
import session.InstrumentParameters.{OptionParameters, FutureParameters}
import com.ergodicity.cgate.scheme.FutInfo.fut_sess_contents
import com.ergodicity.cgate.scheme.OptInfo.opt_sess_contents

case class SessionId(fut: Int, opt: Int)

object SessionsTracking {
  def apply(FutInfoStream: ActorRef, OptInfoStream: ActorRef) = new SessionsTracking(FutInfoStream, OptInfoStream)

  case class SubscribeOngoingSessions(ref: ActorRef)

  case class OngoingSession(id: SessionId, ref: ActorRef)

  case class OngoingSessionTransition(from: OngoingSession, to: OngoingSession)

  // Dispatching events
  case class DropSession(id: SessionId)

  case class SessionEvent(id: SessionId, session: Session, state: SessionState, intradayClearingState: IntradayClearingState)

  case class FutSessContents(sessionId: Int, future: FutureContract, params: FutureParameters, state: InstrumentState)

  case class OptSessContents(sessionId: Int, option: OptionContract, params: OptionParameters)

  case class FutSysEvent(event: SysEvent)

  case class OptSysEvent(event: SysEvent)

  case class PendingEvents(sess: Seq[SessionEvent] = Seq(), fut: Seq[FutSessContents] = Seq(), opt: Seq[OptSessContents] = Seq()) {
    def append(e: SessionEvent) = copy(sess = sess :+ e)

    def append(e: FutSessContents) = copy(fut = fut :+ e)

    def append(e: OptSessContents) = copy(opt = opt :+ e)

    def filterNot(id: SessionId): PendingEvents =
      copy(sess.filterNot(_.id == id), fut.filterNot(_.sessionId == id.fut), opt.filterNot(_.sessionId == id.opt))

    def filter(id: SessionId): (Seq[SessionEvent], Seq[FutSessContents], Seq[OptSessContents]) =
      (sess.filter(_.id == id), fut.filter(_.sessionId == id.fut), opt.filter(_.sessionId == id.opt))
  }

  case class SystemEvents(fut: Seq[FutSysEvent] = Seq(), opt: Seq[OptSysEvent] = Seq()) {
    def append(e: FutSysEvent) = copy(fut = fut :+ e)

    def append(e: OptSysEvent) = copy(opt = opt :+ e)

    def filterNot(eventId: Long) = copy(fut.filterNot(_.event.eventId == eventId), opt.filterNot(_.event.eventId == eventId))

    def synchronized: Option[SynchronizedEvent] = {
      (fut.map(_.event.eventId) intersect opt.map(_.event.eventId)).headOption.flatMap {
        case eventId =>
          (fut.find(_.event.eventId == eventId) zip opt.find(_.event.eventId == eventId)).headOption.map {
            case (FutSysEvent(f), OptSysEvent(o)) => SynchronizedEvent(f, o)
          }
      }
    }
  }

  case class SynchronizedEvent(fut: SysEvent, opt: SysEvent) {
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

class SessionsTracking(FutInfoStream: ActorRef, OptInfoStream: ActorRef) extends Actor with ActorLogging with WhenUnhandled {

  import SessionsTracking._

  implicit val timeout = util.Timeout(1.second)

  var subscribers: List[ActorRef] = Nil

  var ongoingSession: Option[OngoingSession] = None

  // Storage for postponed events
  var systemEvents = SystemEvents()
  var pendingEvents = PendingEvents()

  val sessions = mutable.Map[SessionId, ActorRef]()

  // Dispatch events from FutInfo & OptInfo streams
  val futuresDispatcher = context.actorOf(Props(new FutInfoDispatcher(self, FutInfoStream)), "FutInfoDispatcher")
  val optionsDispatcher = context.actorOf(Props(new OptInfoDispatcher(self, OptInfoStream)), "OptInfoDispatcher")

  override def preStart() {
    log.info("Start session tracking")

  }


  protected def receive =  dispatchContents orElse handleSysEvents orElse handler orElse whenUnhandled

  private def dispatchContents = dispatchSessions orElse dispatchFuturesContents orElse dispatchOptionsContents

  private def dispatchSessions: Receive = {
    case e@SessionEvent(id, _, _, _) if (!sessions.contains(id)) =>
      pendingEvents = pendingEvents append e

    case e@SessionEvent(id, _, state, intState) if (sessions contains id) =>
      sessions(id) ! state
      sessions(id) ! intState
  }

  private def dispatchFuturesContents: Receive = {
    case e@FutSessContents(id, _, _, _) if (!sessions.exists(_._1.fut == id)) =>
      pendingEvents = pendingEvents append e

    case e@FutSessContents(id, _, _, _) if (sessions.exists(_._1.fut == id)) =>
      sessions.find(_._1.fut == id) foreach (_._2 ! e)
  }

  private def dispatchOptionsContents: Receive = {
    case e@OptSessContents(id, _, _) if (!sessions.exists(_._1.opt == id)) =>
      pendingEvents = pendingEvents append e

    case e@OptSessContents(id, _, _) if (sessions.exists(_._1.opt == id)) =>
      sessions.find(_._1.opt == id) foreach (_._2 ! e)
  }

  private def handleSysEvents: Receive = {
    case e: FutSysEvent =>
      systemEvents = systemEvents.append(e)
      systemEvents.synchronized foreach synchronize

    case e: OptSysEvent =>
      systemEvents = systemEvents.append(e)
      systemEvents.synchronized foreach synchronize
  }

  private def handler: Receive = {
    case SubscribeOngoingSessions(ref) =>
      subscribers = ref +: subscribers
      ongoingSession.map(session => ref ! session)

    case DropSession(id) =>
      sessions(id) ! PoisonPill
      sessions.remove(id)
  }

  private def synchronize(event: SynchronizedEvent) {
    log.info("Synchonized event = " + event)
    event match {
      case SynchronizedEvent(SessionDataReady(_, futSessionId), SessionDataReady(_, optSessionId)) =>
        val sessionId = SessionId(futSessionId, optSessionId)
        val (sessionEvents, futContents, optContents) = pendingEvents.filter(sessionId)

        for (sessionEvent <- sessionEvents) {
          val sessionActor = sessions.getOrElseUpdate(sessionId, context.actorOf(Props(new SessionActor(sessionEvent.session)), sessionId.fut.toString))
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

        systemEvents = systemEvents.filterNot(event.eventId)
        pendingEvents = pendingEvents.filterNot(sessionId)

      case _ =>
        log.debug("Remove ignored event = " + event)
        systemEvents = systemEvents.filterNot(event.eventId)
    }
  }

  private def changeOngoingSession(id: SessionId) {
    val newOngoingSession = OngoingSession(id, sessions(id))
    ongoingSession match {
      case None => subscribers.foreach(_ ! newOngoingSession)
      case Some(old) if (old != newOngoingSession) => subscribers.foreach(_ ! OngoingSessionTransition(old, newOngoingSession))
    }
    ongoingSession = Some(newOngoingSession)
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
        val instrument = implicitly[ToInstrument[fut_sess_contents, FutureContract, FutureParameters]]
        val security = instrument security record
        val parameters = instrument parameters record
        val state = InstrumentState(record.get_state())
        sessionsTracking ! FutSessContents(record.get_sess_id(), security, parameters, state)
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
        val instrument = implicitly[ToInstrument[opt_sess_contents, OptionContract, OptionParameters]]
        val security = instrument security record
        val parameters = instrument parameters record
        sessionsTracking ! OptSessContents(record.get_sess_id(), security, parameters)
      }

    case StreamData(OptInfo.sys_events.TABLE_INDEX, data) =>
      val record = implicitly[Reads[OptInfo.sys_events]] apply data
      if (record.get_replAct() == 0) {
        sessionsTracking ! OptSysEvent(SysEvent(record))
      }
  }
}

