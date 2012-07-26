package com.ergodicity.core.session

import akka.actor.{FSM, Props, ActorRef, Actor}
import akka.actor.FSM.{SubscribeTransitionCallBack, CurrentState, Transition}
import com.ergodicity.cgate.repository.Repository.Snapshot
import com.ergodicity.core.common.{FullIsin, Security}

sealed trait SessionContentsState

object SessionContentsState {

  case object Binding extends SessionContentsState

  case object TrackingSession extends SessionContentsState

}

trait SessionContents[S <: Security, R <: SessContents] extends Actor with FSM[SessionContentsState, Option[SessionState]] {

  import SessionContentsState._

  def SessionRef: ActorRef

  def converter: R => S

  protected[core] var instruments: Map[FullIsin, ActorRef] = Map()

  // Subscribe for session states
  SessionRef ! SubscribeTransitionCallBack(self)

  startWith(Binding, None)

  when(Binding) {
    case Event(CurrentState(ref, state: SessionState), _) if (ref == SessionRef) => goto(TrackingSession) using Some(state)
  }

  when(TrackingSession) {
    case Event(Transition(ref, _, to: SessionState), _) if (ref == SessionRef) => handleSessionState(to); stay() using Some(to)
    case Event(Snapshot(_, data), _) => handleSessionContentsRecord(data.asInstanceOf[Iterable[R]]); stay()
  }

  whenUnhandled {
    case Event(GetSessionInstrument(isin), _) =>
      val i = instruments.get(isin)
      log.debug("Get session instrument: " + isin + "; Result = " + i)
      sender ! i
      stay()
  }

  protected def handleSessionState(state: SessionState)

  protected def handleSessionContentsRecord(records: Iterable[R])

  def conformIsinToActorName(isin: String): String = isin.replaceAll(" ", "@")

  def conformIsinToActorName(isin: FullIsin): String = conformIsinToActorName(isin.isin)
}

case class StatefulSessionContents[S <: Security, R <: StatefulSessContents](SessionRef: ActorRef)(implicit val converter: R => S) extends SessionContents[S, R] {

  private var originalInstrumentState: Map[FullIsin, InstrumentState] = Map()

  protected def handleSessionState(state: SessionState) {
    instruments.foreach {
      case (isin, ref) => ref ! mergeStates(state, originalInstrumentState(isin))
    }
  }

  def handleSessionContentsRecord(records: Iterable[R]) {
    records.foreach {
      record =>
        val isin = record2isin(record)
        val state = mergeStates(stateData.get, InstrumentState(record.get_state()))
        instruments.get(isin).map(_ ! state) getOrElse {
          instruments = instruments + (isin -> context.actorOf(Props(new Instrument(converter(record), state)), isin.isin))
        }
        originalInstrumentState = originalInstrumentState + (isin -> InstrumentState(record.get_state()))
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

case class StatelessSessionContents[S <: Security, R <: StatelessSessContents](SessionRef: ActorRef)(implicit val converter: R => S) extends SessionContents[S, R] {
  protected def handleSessionState(state: SessionState) {
    instruments.foreach {
      case (isin, ref) => ref ! InstrumentState(state)
    }
  }

  protected def handleSessionContentsRecord(records: Iterable[R]) {
    records.foreach {
      record =>
        val isin = record2isin(record)
        val state = InstrumentState(stateData.get)
        instruments.get(isin).map(_ ! state) getOrElse {
          instruments = instruments + (isin -> context.actorOf(Props(new Instrument(converter(record), state)), conformIsinToActorName(isin)))
        }
    }
  }
}