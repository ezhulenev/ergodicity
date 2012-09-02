package com.ergodicity.core.session

import akka.actor.{ActorLogging, Props, ActorRef, Actor}
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.pattern.ask
import akka.util.duration._
import com.ergodicity.cgate.repository.Repository.Snapshot
import com.ergodicity.cgate.WhenUnhandled
import collection.mutable
import com.ergodicity.core.session.SessionActor.{AssignedInstruments, GetAssignedInstruments, GetInstrumentActor, GetState}
import akka.dispatch.Await
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}
import com.ergodicity.core.session.Instrument.Limits
import akka.util.Timeout
import Implicits._

trait ContentsManager[T] {
  contents: SessionContents[T] =>

  protected def handleSessionState(state: SessionState)

  protected def handleSessionContentsRecord(records: Iterable[T])
}

class SessionContents[T](Session: ActorRef)(implicit val toSecurity: ToSecurity[T]) extends Actor with ActorLogging with WhenUnhandled {
  manager: SessionContents[T] with ContentsManager[T] =>

  implicit val timeout = Timeout(100.millis)

  protected[core] val instruments = mutable.Map[Instrument, ActorRef]()

  var sessionState = Await.result((Session ? GetState).mapTo[SessionState], 500.millis)

  override def preStart() {
    log.info("Start SessionContents with parent session state = " + sessionState)
    Session ! SubscribeTransitionCallBack(self)
  }

  protected def receive = receiveSessionState orElse getInstruments orElse receiveSnapshot orElse whenUnhandled

  private def receiveSessionState: Receive = {
    case CurrentState(Session, state: SessionState) =>
      sessionState = state

    case Transition(Session, _, to: SessionState) =>
      sessionState = to
      handleSessionState(to)
  }

  private def getInstruments: Receive = {
    case GetInstrumentActor(isin) =>
      val instrument = instruments.find(_._1.security.isin == isin)
      log.debug("Get session instrument: " + isin + "; Result = " + instrument)
      sender ! instrument.map(_._2)

    case GetAssignedInstruments =>
      sender ! AssignedInstruments(instruments.keys.toSet)
  }

  private def receiveSnapshot: Receive = {
    case Snapshot(_, data) =>
      handleSessionContentsRecord(data.asInstanceOf[Iterable[T]])
  }
}

trait FuturesContentsManager extends ContentsManager[FutInfo.fut_sess_contents] {
  contents: SessionContents[FutInfo.fut_sess_contents] =>

  private val originalInstrumentState = mutable.Map[Instrument, InstrumentState]()

  protected def handleSessionState(state: SessionState) {
    instruments.foreach {
      case (instrument, ref) => ref ! mergeStates(state, originalInstrumentState(instrument))
    }
  }

  def handleSessionContentsRecord(records: Iterable[FutInfo.fut_sess_contents]) {
    records.foreach {
      record =>
        val instrument = Instrument(toSecurity.convert(record), Limits(record.get_limit_down(), record.get_limit_up()))
        originalInstrumentState(instrument)  = InstrumentState(record.get_state())

        val mergedState = mergeStates(sessionState, InstrumentState(record.get_state()))
        val instrumentActor = instruments.getOrElseUpdate(instrument,context.actorOf(Props(new InstrumentActor(instrument)), record.isin.toActorName))
        instrumentActor ! mergedState

    }
  }

  def mergeStates(sessionState: SessionState, instrumentState: InstrumentState) = sessionState match {

    case SessionState.Assigned => instrumentState match {
      case InstrumentState.Online => InstrumentState.Assigned
      case other => other
    }

    case SessionState.Online => instrumentState

    case SessionState.Suspended => instrumentState match {
      case s@(InstrumentState.Canceled | InstrumentState.Completed) => s
      case s@(InstrumentState.Assigned | InstrumentState.Online) => InstrumentState.Suspended
      case other => other
    }

    case SessionState.Canceled => InstrumentState.Canceled

    case SessionState.Completed => InstrumentState.Completed
  }
}

trait OptionsContentsManager extends ContentsManager[OptInfo.opt_sess_contents] {
  contents: SessionContents[OptInfo.opt_sess_contents] =>

  protected def handleSessionState(state: SessionState) {
    instruments.foreach {
      case (isin, ref) => ref ! InstrumentState(state)
    }
  }

  protected def handleSessionContentsRecord(records: Iterable[OptInfo.opt_sess_contents]) {
    records.foreach {
      record =>
        val state = InstrumentState(sessionState)
        val instrument = Instrument(toSecurity.convert(record), Limits(record.get_limit_down(), record.get_limit_up()))
        val instrumentActor = instruments.getOrElseUpdate(instrument, context.actorOf(Props(new InstrumentActor(instrument)), record.isin.toActorName))
        instrumentActor ! state
    }
  }
}