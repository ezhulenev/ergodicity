package com.ergodicity.engine.service

import com.ergodicity.engine._
import akka.actor._
import akka.util.duration._
import akka.pattern.ask
import akka.pattern.pipe
import com.ergodicity.cgate.{Connection => _, _}
import com.ergodicity.engine.Components.{CreateListener, FutInfoReplication, OptInfoReplication}
import repository.Repository
import repository.Repository.{GetSnapshot, SubscribeSnapshots, Snapshot}
import service.Service.{Stop, Start}
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import scheme.{OptInfo, FutInfo}
import sysevents.SysEvent.SessionDataReady
import sysevents.SysEventDispatcher.SubscribeSysEvents
import sysevents.{SysEventDispatcher, SysEvent}
import akka.util.Timeout
import collection.mutable
import akka.actor.FSM.Transition
import akka.actor.FSM.UnsubscribeTransitionCallBack
import scala.Some
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.cgate.DataStream.{BindingSucceed, BindingResult, BindTable}
import akka.dispatch.Await
import com.ergodicity.core.session.{Session, SessionContent, IntradayClearingState, SessionState}
import com.ergodicity.core.session.Session.{FutInfoSessionContents, OptInfoSessionContents}
import scalaz._
import Scalaz._

case object SessionsService extends Service

trait Sessions {
  engine: Engine with Connection with CreateListener with FutInfoReplication with OptInfoReplication =>

  def FutInfoStream: ActorRef

  def OptInfoStream: ActorRef

  def Sessions: ActorRef
}

trait ManagedSessions extends Sessions {
  engine: Engine with Connection with CreateListener with FutInfoReplication with OptInfoReplication =>

  val FutInfoStream = context.actorOf(Props(new DataStream), "FutInfoDataStream")
  val OptInfoStream = context.actorOf(Props(new DataStream), "OptInfoDataStream")

  val Sessions = context.actorOf(Props(new SessionsTracking(FutInfoStream, OptInfoStream)), "SessionsTracking")
  private[this] val sessionsManager = context.actorOf(Props(new SessionsManager(this)).withDispatcher("deque-dispatcher"), "SessionsManager")

  registerService(SessionsService, sessionsManager)
}

protected[service] class SessionsManager(engine: Engine with Connection with Sessions with CreateListener with FutInfoReplication with OptInfoReplication) extends Actor with ActorLogging with WhenUnhandled with Stash {

  import engine._

  val ManagedSessions = Sessions

  // Listeners
  val underlyingFutInfoListener = listener(underlyingConnection, futInfoReplication(), new DataStreamSubscriber(FutInfoStream))
  val futInfoListener = context.actorOf(Props(new Listener(underlyingFutInfoListener)), "FutInfoListener")

  val underlyingOptInfoListener = listener(underlyingConnection, optInfoReplication(), new DataStreamSubscriber(OptInfoStream))
  val optInfoListener = context.actorOf(Props(new Listener(underlyingOptInfoListener)), "OptInfoListener")

  protected def receive = {
    case ServiceStarted(ConnectionService) =>
      log.info("ConnectionService started, unstash all messages and start SessionsService")
      unstashAll()
      context.become {
        start orElse stop orElse handleSessionsGoesOnline orElse whenUnhandled
      }

    case msg =>
      log.info("Stash message until ConnectionService is not started = " + msg)
      stash()
  }

  private def start: Receive = {
    case Start =>
      futInfoListener ! Listener.Open(ReplicationParams(Combined))
      optInfoListener ! Listener.Open(ReplicationParams(Combined))
      ManagedSessions ! SubscribeTransitionCallBack(self)
  }

  private def handleSessionsGoesOnline: Receive = {
    case CurrentState(ManagedSessions, SessionsTrackingState.Online) =>
      ManagedSessions ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(SessionsService)

    case Transition(ManagedSessions, _, SessionsTrackingState.Online) =>
      ManagedSessions ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(SessionsService)
  }

  private def stop: Receive = {
    case Stop =>
      futInfoListener ! Listener.Close
      optInfoListener ! Listener.Close
      futInfoListener ! Listener.Dispose
      optInfoListener ! Listener.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        ServiceManager ! ServiceStopped(SessionsService)
        context.stop(self)
      }
  }
}


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
