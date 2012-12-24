package com.ergodicity.core.session

import org.joda.time.Interval
import akka.actor._
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import com.ergodicity.cgate.scheme.FutInfo
import scala.Some
import com.ergodicity.core.{Isin, Security, SessionId, IsinId}
import collection.immutable
import com.ergodicity.core.SessionsTracking.{OptSessContents, FutSessContents}


class InstrumentNotAssigned(id: Any) extends RuntimeException("No such instument assigned: " + id)

object Session {
  def from(rec: FutInfo.session) = Session(
    SessionId(rec.get_sess_id(), rec.get_opt_sess_id()),
    new Interval(rec.get_begin(), rec.get_end()),
    if (rec.get_eve_on() != 0) Some(new Interval(rec.get_eve_begin(), rec.get_eve_end())) else None,
    if (rec.get_mon_on() != 0) Some(new Interval(rec.get_mon_begin(), rec.get_mon_end())) else None,
    new Interval(rec.get_pos_transfer_begin(), rec.get_pos_transfer_end())
  )

}
case class Session(id: SessionId, primarySession: Interval, eveningSession: Option[Interval], morningSession: Option[Interval], positionTransfer: Interval)

object SessionActor {
  implicit val timeout = Timeout(1, TimeUnit.SECONDS)

  // Failures

  class IllegalLifeCycleEvent(msg: String, event: Any) extends RuntimeException(msg)

  // Actions

  case object GetState

  case object GetAssignedContents

  case class AssignedContents(contents: immutable.Set[Security]) {
    import scalaz._
    import Scalaz._

    val findByIdMemo = immutableHashMapMemo[IsinId, Option[Security]] {
      id => contents.find(_.id == id)
    }

    val findByIsinMemo = immutableHashMapMemo[Isin, Option[Security]] {
      isin => contents.find(_.isin == isin)
    }

    def ?(id: IsinId) = findByIdMemo(id)

    def ?(isin: Isin) = findByIsinMemo(isin)
  }

  case class GetInstrument(security: Security)

  case class InstrumentRef(session: SessionId, security: Security, instrumentActor: ActorRef)
}


case class SessionActor(session: Session) extends Actor with LoggingFSM[SessionState, Unit] {

  import SessionActor._
  import SessionState._

  val intradayClearing = context.actorOf(Props(new IntradayClearing), "IntradayClearing")

  // Session contents


  val futures = context.actorOf(Props(new SessionContents[FutSessContents](self) with FuturesContentsManager), "Futures")
  val options = context.actorOf(Props(new SessionContents[OptSessContents](self) with OptionsContentsManager), "Options")

  startWith(Assigned, ())

  when(Assigned) {
    handleSessionState orElse handleIntradayClearingState orElse handleSessContents
  }
  when(Online) {
    handleSessionState orElse handleIntradayClearingState orElse handleSessContents
  }
  when(Suspended) {
    handleSessionState orElse handleIntradayClearingState orElse handleSessContents
  }

  when(Canceled)(handleIntradayClearingState orElse handleSessContents orElse {
    case Event(SessionState.Canceled, _) => stay()
    case Event(e: SessionState, _) => throw new IllegalLifeCycleEvent("Unexpected event after canceled", e)
  })

  when(Completed)(handleIntradayClearingState orElse handleSessContents orElse {
    case Event(SessionState.Completed, _) => stay()
    case Event(e: SessionState, _) => throw new IllegalLifeCycleEvent("Unexpected event after completion", e)
  })

  whenUnhandled {
    case Event(GetState, _) =>
      sender ! stateName
      stay()

    case Event(GetInstrument(security), _) =>
      val future = (futures ? GetInstrument(security)).mapTo[Option[ActorRef]]
      val option = (options ? GetInstrument(security)).mapTo[Option[ActorRef]]

      future zip option map {
        case (f, o) => (f orElse o).map(InstrumentRef(session.id, security, _)).getOrElse(throw new InstrumentNotAssigned(security))
      } pipeTo sender

      stay()

    case Event(GetAssignedContents, _) =>
      val assignedFutures = (futures ? GetAssignedContents).mapTo[AssignedContents]
      val assignedOptions = (options ? GetAssignedContents).mapTo[AssignedContents]

      assignedFutures zip assignedOptions map {
        case (AssignedContents(fut), AssignedContents(opt)) => AssignedContents(fut ++ opt)
      } pipeTo sender

      stay()
  }

  onTransition {
    case from -> to => log.info("Session transition from {} to {}", from, to)
  }

  initialize

  private def handleSessContents: StateFunction = {
    case Event(c: FutSessContents, _) =>
      futures ! c
      stay()

    case Event(c: OptSessContents, _) =>
      options ! c
      stay()
  }

  private def handleSessionState: StateFunction = {
    case Event(state: SessionState, _) =>
      goto(state)
  }

  private def handleIntradayClearingState: StateFunction = {
    case Event(state: IntradayClearingState, _) =>
      intradayClearing ! state
      stay()
  }
}

class IntradayClearing extends Actor with LoggingFSM[IntradayClearingState, Unit] {

  import IntradayClearingState._

  startWith(Undefined, ())

  when(Undefined) {
    handleState
  }
  when(Oncoming) {
    handleState
  }
  when(Canceled) {
    handleState
  }
  when(Running) {
    handleState
  }
  when(Finalizing) {
    handleState
  }
  when(Completed) {
    handleState
  }

  initialize

  private def handleState: StateFunction = {
    case Event(s: IntradayClearingState, _) => goto(s)
  }
}