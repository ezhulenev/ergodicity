package com.ergodicity.core.session

import akka.actor.{ActorLogging, Props, ActorRef, Actor}
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.pattern.ask
import akka.util.duration._
import com.ergodicity.cgate.repository.Repository.Snapshot
import com.ergodicity.cgate.WhenUnhandled
import com.ergodicity.core.{Isin, Isins}
import collection.mutable
import com.ergodicity.core.session.Session.GetState
import akka.dispatch.Await
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}
import com.ergodicity.core.session.InstrumentData.Limits
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

  protected[core] val instruments = mutable.Map[Isin, ActorRef]()

  var sessionState = Await.result((Session ? GetState).mapTo[SessionState], 500.millis)

  override def preStart() {
    log.info("Start SessionContents with parent session state = " + sessionState)
    Session ! SubscribeTransitionCallBack(self)
  }

  protected def receive = receiveSessionState orElse getInstrumet orElse receiveSnapshot orElse whenUnhandled

  private def receiveSessionState: Receive = {
    case CurrentState(Session, state: SessionState) =>
      sessionState = state

    case Transition(Session, _, to: SessionState) =>
      sessionState = to
      handleSessionState(to)
  }

  private def getInstrumet: Receive = {
    case GetSessionInstrument(isin) =>
      val i = instruments.get(isin)
      log.debug("Get session instrument: " + isin + "; Result = " + i)
      sender ! i
  }

  private def receiveSnapshot: Receive = {
    case Snapshot(_, data) =>
      handleSessionContentsRecord(data.asInstanceOf[Iterable[T]])
  }

  def conformIsinToActorName(isin: String): String = isin.replaceAll(" ", "@")

  def conformIsinToActorName(isin: Isins): String = conformIsinToActorName(isin.isin)
}

trait FuturesContentsManager extends ContentsManager[FutInfo.fut_sess_contents] {
  contents: SessionContents[FutInfo.fut_sess_contents] =>

  private var originalInstrumentState: Map[Isin, InstrumentState] = Map()

  protected def handleSessionState(state: SessionState) {
    instruments.foreach {
      case (isin, ref) => ref ! mergeStates(state, originalInstrumentState(isin))
    }
  }

  def handleSessionContentsRecord(records: Iterable[FutInfo.fut_sess_contents]) {
    records.foreach {
      record =>
        val isin = record.isin
        val state = mergeStates(sessionState, InstrumentState(record.get_state()))
        instruments.get(isin).map(_ ! state) getOrElse {
          val data = InstrumentData(toSecurity.convert(record), Limits(record.get_limit_down(), record.get_limit_up()))
          instruments(isin) = context.actorOf(Props(new Instrument(state, data)), isin.isin)
        }
        originalInstrumentState = originalInstrumentState + (isin -> InstrumentState(record.get_state()))
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
        val isin = record.isin
        val state = InstrumentState(sessionState)
        instruments.get(isin).map(_ ! state) getOrElse {
          val data = InstrumentData(toSecurity.convert(record), Limits(record.get_limit_down(), record.get_limit_up()))
          instruments(isin) = context.actorOf(Props(new Instrument(state, data)), conformIsinToActorName(isin))
        }
    }
  }
}