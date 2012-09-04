package com.ergodicity.core.session

import org.joda.time.Interval
import akka.actor._
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}
import com.ergodicity.cgate.repository.Repository.Snapshot
import scala.Some
import com.ergodicity.core.{IsinId, Isin}
import collection.immutable


case class Session(id: Int, optionsSessionId: Int, primarySession: Interval, eveningSession: Option[Interval], morningSession: Option[Interval], positionTransfer: Interval) {
  def this(rec: FutInfo.session) = this(
    rec.get_sess_id(),
    rec.get_opt_sess_id(),
    new Interval(rec.get_begin(), rec.get_end()),
    if (rec.get_eve_on() != 0) Some(new Interval(rec.get_eve_begin(), rec.get_eve_end())) else None,
    if (rec.get_mon_on() != 0) Some(new Interval(rec.get_mon_begin(), rec.get_mon_end())) else None,
    new Interval(rec.get_pos_transfer_begin(), rec.get_pos_transfer_end())
  )
}

object SessionActor {
  implicit val timeout = Timeout(1, TimeUnit.SECONDS)

  def apply(rec: FutInfo.session) = {
    new SessionActor(
      new Session(rec),
      SessionState(rec.get_state()),
      IntradayClearingState(rec.get_inter_cl_state())
    )
  }

  // Failures

  class IllegalLifeCycleEvent(msg: String, event: Any) extends RuntimeException(msg)

  class InstrumentIdNotAssigned(id: IsinId) extends RuntimeException("No such instument assigned: " + id)

  class InstrumentIsinNotAssigned(isin: Isin) extends RuntimeException("No such instument assigned: " + isin)


  // Session contents

  case class FutInfoSessionContents(snapshot: Snapshot[FutInfo.fut_sess_contents])

  case class OptInfoSessionContents(snapshot: Snapshot[OptInfo.opt_sess_contents])

  // Actions

  case object GetState

  case object GetAssignedInstruments

  case class AssignedInstruments(instruments: immutable.Set[Instrument]) {
    import scalaz._
    import Scalaz._

    val findByIdMemo = immutableHashMapMemo[IsinId, Option[Instrument]] {
      id => instruments.find(_.security.id == id)
    }

    val findByIsinMemo =  immutableHashMapMemo[Isin, Option[Instrument]] {
      isin => instruments.find(_.security.isin == isin)
    }

    def isin(id: IsinId) = findByIdMemo(id).getOrElse(throw new InstrumentIdNotAssigned(id)).security.isin
  }

  case class GetInstrumentActor(isin: Isin)

}


case class SessionActor(content: Session, initialState: SessionState, initialIntradayClearingState: IntradayClearingState) extends Actor with LoggingFSM[SessionState, Unit] {

  import SessionActor._
  import SessionState._

  val intradayClearing = context.actorOf(Props(new IntradayClearing(initialIntradayClearingState)), "IntradayClearing")

  // Session contents

  import Implicits._

  val futures = context.actorOf(Props(new SessionContents[FutInfo.fut_sess_contents](self) with FuturesContentsManager), "Futures")
  val options = context.actorOf(Props(new SessionContents[OptInfo.opt_sess_contents](self) with OptionsContentsManager), "Options")

  startWith(initialState, ())

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

    case Event(GetInstrumentActor(isin), _) =>
      val future = (futures ? GetInstrumentActor(isin)).mapTo[Option[ActorRef]]
      val option = (options ? GetInstrumentActor(isin)).mapTo[Option[ActorRef]]

      future zip option map {
        case (f, o) => (f orElse o).getOrElse(throw new InstrumentIsinNotAssigned(isin))
      } pipeTo sender

      stay()

    case Event(GetAssignedInstruments, _) =>
      val assignedFutures = (futures ? GetAssignedInstruments).mapTo[AssignedInstruments]
      val assignedOptions = (options ? GetAssignedInstruments).mapTo[AssignedInstruments]

      assignedFutures zip assignedOptions map {
        case (AssignedInstruments(fut), AssignedInstruments(opt)) => AssignedInstruments(fut ++ opt)
      } pipeTo sender

      stay()
  }

  initialize

  private def handleSessContents: StateFunction = {
    case Event(FutInfoSessionContents(snapshot), _) =>
      futures ! snapshot.filter(_.isFuture)
      stay()

    case Event(OptInfoSessionContents(snapshot), _) =>
      options ! snapshot
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

case class IntradayClearing(initialState: IntradayClearingState) extends Actor with LoggingFSM[IntradayClearingState, Unit] {

  import IntradayClearingState._

  override def preStart() {
    log.info("Start IntradayClearing actor in state = " + initialState)
  }

  startWith(initialState, ())

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