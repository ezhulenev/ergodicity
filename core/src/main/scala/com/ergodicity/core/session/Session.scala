package com.ergodicity.core.session

import org.joda.time.Interval
import akka.actor.{ActorRef, Props, Actor, FSM}
import akka.pattern.ask
import akka.actor.FSM._
import com.ergodicity.core.common.{FullIsin, FutureContract, OptionContract}
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import com.ergodicity.cgate.repository.Repository.Snapshot
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}


case class SessionContent(id: Int, optionsSessionId: Int, primarySession: Interval, eveningSession: Option[Interval], morningSession: Option[Interval], positionTransfer: Interval) {
  def this(rec: FutInfo.session) = this(
    rec.get_sess_id(),
    rec.get_opt_sess_id(),
    new Interval(rec.get_begin(), rec.get_end()),
    if (rec.get_eve_on() != 0) Some(new Interval(rec.get_eve_begin(), rec.get_eve_end())) else None,
    if (rec.get_mon_on() != 0) Some(new Interval(rec.get_mon_begin(), rec.get_mon_end())) else None,
    new Interval(rec.get_pos_transfer_begin(), rec.get_pos_transfer_end())
  )
}


object Session {
  implicit val timeout = Timeout(1, TimeUnit.SECONDS)

  def apply(rec: FutInfo.session) = {
    new Session(
      new SessionContent(rec),
      SessionState(rec.get_state()),
      IntClearingState(rec.get_inter_cl_state())
    )
  }

  case class FutInfoSessionContents(snapshot: Snapshot[FutInfo.fut_sess_contents])

  case class OptInfoSessionContents(snapshot: Snapshot[OptInfo.opt_sess_contents])
}

case class GetSessionInstrument(isin: FullIsin)

case class Session(content: SessionContent, state: SessionState, intClearingState: IntClearingState) extends Actor with FSM[SessionState, ActorRef] {

  import Session._
  import SessionState._

  val intClearing = context.actorOf(Props(new IntClearing(intClearingState)), "IntClearing")

  val futures = context.actorOf(Props(new StatefulSessionContents[FutureContract, FutInfo.fut_sess_contents](state)), "Futures")
  futures ! TrackSessionState(self)

  val options = context.actorOf(Props(new StatelessSessionContents[OptionContract, OptInfo.opt_sess_contents](state)), "Options")
  options ! TrackSessionState(self)


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

  whenUnhandled {
    case Event(GetSessionInstrument(isin), _) =>
      val replyTo = sender

      val future = (futures ? GetSessionInstrument(isin)).mapTo[Option[ActorRef]]
      val option = (options ? GetSessionInstrument(isin)).mapTo[Option[ActorRef]]

      val instrument = for {
        f ← future
        o ← option
      } yield f orElse o

      instrument onComplete {_.fold(_ => replyTo ! None, replyTo ! _)}
      stay()
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