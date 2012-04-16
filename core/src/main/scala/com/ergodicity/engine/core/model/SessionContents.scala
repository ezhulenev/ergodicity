package com.ergodicity.engine.core.model

import com.ergodicity.engine.plaza2.Repository.Snapshot
import akka.actor.{FSM, Props, ActorRef, Actor}
import akka.actor.FSM.{SubscribeTransitionCallBack, CurrentState, Transition}

case class TrackSession(session: ActorRef)

trait SessionContents[S <: Security, R <: SessContents] extends Actor with FSM[SessionState, Unit] {
  import SessionState._
  
  def converter: R => S
  
  protected[core] var instruments: Map[String, ActorRef] = Map()

  when(Assigned) {
    handleSessionTransitions orElse handleSessionContents
  }
  when(Online) {
    handleSessionTransitions orElse handleSessionContents
  }
  when(Suspended) {
    handleSessionTransitions orElse handleSessionContents
  }
  when(Canceled) {
    handleSessionTransitions orElse handleSessionContents
  }
  when(Completed) {
    handleSessionTransitions orElse handleSessionContents
  }

  onTransition {
    case from -> to => log.info("SessionContents updated from " + from + " -> " + to)
  }

  whenUnhandled
  private def handleTrackSession: StateFunction = {
    case Event(TrackSession(session), _) => session ! SubscribeTransitionCallBack(self); stay()
  }
  
  private def handleSessionTransitions: StateFunction = {
    case Event(CurrentState(_, s: SessionState), _) =>
      startWith(s, ())
      initialize
      goto(s)

    case Event(Transition(_, from: SessionState, to: SessionState), _) => goto(to)
  }

  private def handleSessionContents: StateFunction = {
    case Event(snapshot: Snapshot[R], _) => handleSessionContentsRecord(snapshot.data); stay()
  }

  protected def handleSessionContentsRecord(records: Iterable[R]);
}

class StatefulSessionContents[S <: Security, R <: StatefulSessContents](implicit val converter: R => S) extends SessionContents[S, R] {

  private var originalInstrumentState: Map[String, InstrumentState]  = Map()

  onTransition {
    case _ -> to => instruments.foreach {
      case (isin, ref) => ref ! mergeStates(to, originalInstrumentState(isin))
    }
  }

  def handleSessionContentsRecord(records: Iterable[R]) {
    records.foreach {
      record =>
        val isin = record.isin
        val state = mergeStates(stateName, InstrumentState(record.state))
        instruments.get(isin).map(_ ! state) getOrElse {
          instruments = instruments + (isin -> context.actorOf(Props(new Instrument(converter(record), state)), isin))
        }
        originalInstrumentState = originalInstrumentState + (isin -> InstrumentState(record.state))
    }
  }

  def mergeStates(sessionState: SessionState, instrumentState: InstrumentState) = {
    sessionState match {

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
}

class StatelessSessionContents[S <: Security, R <: StatelessSessContents](implicit val converter: R => S) extends SessionContents[S, R] {
  onTransition {
    case _ -> to => instruments.foreach {
      case (isin, ref) => ref ! InstrumentState(to)
    }
  }

  protected def handleSessionContentsRecord(records: Iterable[R]) {
    records.foreach {
      record =>
        val isin = record.isin
        val state = InstrumentState(stateName)
        instruments.get(isin).map(_ ! state) getOrElse {
          instruments = instruments + (record.isin -> context.actorOf(Props(new Instrument(converter(record), state)), isin))
        }
    }
  }
}