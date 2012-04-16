package com.ergodicity.engine.core.model

import com.ergodicity.engine.plaza2.Repository.Snapshot
import akka.actor.{FSM, Props, ActorRef, Actor}
import akka.dispatch.Await
import akka.util.duration._
import akka.pattern.ask
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.util.Timeout


trait SessionContents[S <: Security, R <: SessContents] extends Actor with FSM[SessionState, Unit] {
  import SessionState._

  def session: ActorRef

  def converter: R => S

  protected[core] var instruments: Map[String, ActorRef] = Map()

  session ! SubscribeTransitionCallBack(self)

  implicit val timeout = Timeout(1.second)
  val initialState = Await.result(session ? SubscribeTransitionCallBack(self) map {
    case CurrentState(_, state: SessionState) => state
  }, 1.second)

  startWith(initialState, ())

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

  initialize

  private def handleSessionTransitions: StateFunction = {
    case Event(Transition(ref, from: SessionState, to: SessionState), _) if (ref == session) => goto(to)
  }

  private def handleSessionContents: StateFunction = {
    case Event(snapshot: Snapshot[R], _) => handleSessionContentsRecord(snapshot.data); stay()
  }

  protected def handleSessionContentsRecord(records: Iterable[R]);
}

class StatefulSessionContents[S <: Security, R <: StatefulSessContents](val session: ActorRef)(implicit val converter: R => S) extends SessionContents[S, R] {

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

class StatelessSessionContents[S <: Security, R <: StatelessSessContents](val session: ActorRef)(implicit val converter: R => S) extends SessionContents[S, R] {
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