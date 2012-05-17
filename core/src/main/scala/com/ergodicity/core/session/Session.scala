package com.ergodicity.core.session

import org.joda.time.Interval
import org.scala_tools.time.Implicits._
import akka.actor.{ActorRef, Props, Actor, FSM}
import com.ergodicity.plaza2.scheme.FutInfo._
import akka.actor.FSM._
import com.ergodicity.plaza2.Repository.Snapshot
import com.ergodicity.plaza2.scheme.{OptInfo, FutInfo}
import com.ergodicity.core.session.Session.{OptInfoSessionContents, FutInfoSessionContents}

case class SessionContent(id: Int, optionsSessionId: Int, primarySession: Interval, eveningSession: Option[Interval], morningSession: Option[Interval], positionTransfer: Interval) {
  def this(rec: SessionRecord) = this(
    rec.sessionId,
    rec.optionsSessionId,
    parseInterval(rec.begin, rec.end),
    if (rec.eveOn != 0) Some(parseInterval(rec.eveBegin, rec.eveEnd)) else None,
    if (rec.monOn != 0) Some(parseInterval(rec.monBegin, rec.monEnd)) else None,
    TimeFormat.parseDateTime(rec.posTransferBegin) to TimeFormat.parseDateTime(rec.posTransferEnd)
  )
}


object Session {
  def apply(rec: SessionRecord) = {
    new Session(
      new SessionContent(rec),
      SessionState(rec.state),
      IntClearingState(rec.interClState)
    )
  }

  case class FutInfoSessionContents(snapshot: Snapshot[FutInfo.SessContentsRecord])

  case class OptInfoSessionContents(snapshot: Snapshot[OptInfo.SessContentsRecord])

}

case class Session(content: SessionContent, state: SessionState, intClearingState: IntClearingState) extends Actor with FSM[SessionState, ActorRef] {

  import SessionState._

  val intClearing = context.actorOf(Props(new IntClearing(intClearingState)), "IntClearing")

  val futures = context.actorOf(Props(new StatefulSessionContents[FutureContract, FutInfo.SessContentsRecord](state)), "Futures")
  futures ! JoinSession(self)

  val options = context.actorOf(Props(new StatelessSessionContents[OptionContract, OptInfo.SessContentsRecord](state)), "Options")
  options ! JoinSession(self)


  startWith(state, intClearing)

  when(Assigned) {
    handleSessionState orElse handleClearingState orElse handleSessContents
  }
  when(Online) {
    handleSessionState orElse handleClearingState orElse handleSessContents
  }
  when(Suspended) {
    handleSessionState orElse handleClearingState orElse handleSessContents
  }

  when(Canceled) {
    case Event(SessionState.Canceled, _) => stay()
    case Event(e: SessionState, _) => stop(Failure("Unexpected event after canceled: " + e))
  }

  when(Canceled) {
    handleClearingState orElse handleSessContents
  }

  when(Completed) {
    case Event(SessionState.Completed, _) => stay()
    case Event(e: SessionState, _) => stop(Failure("Unexpected event after completion: " + e))
  }

  when(Completed) {
    handleClearingState orElse handleSessContents
  }

  onTransition {
    case from -> to => log.info("Session updated from " + from + " -> " + to)
  }

  initialize

  log.info("Created session; Id = " + content.id + "; State = " + state + "; content = " + content)

  private def handleSessContents: StateFunction = {
    case Event(FutInfoSessionContents(snapshot), _) => futures ! snapshot.filter(isFuture _); stay()
    case Event(OptInfoSessionContents(snapshot), _) => options ! snapshot; stay()
  }

  private def handleSessionState: StateFunction = {
    case Event(state: SessionState, _) => goto(state)
  }

  private def handleClearingState: StateFunction = {
    case Event(state: IntClearingState, clearing) => clearing ! state; stay()
  }
}

case class IntClearing(state: IntClearingState) extends Actor with FSM[IntClearingState, Unit] {

  import IntClearingState._

  startWith(state, ())

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

  onTransition {
    case from -> to => log.info("Intermediate clearing updated from " + from + " -> " + to)
  }

  initialize

  private def handleState: StateFunction = {
    case Event(s: IntClearingState, _) => goto(s)
  }
}