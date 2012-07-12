package com.ergodicity.engine.extensions

import com.ergodicity.engine.TradingEngine
import akka.actor.{FSM, Actor, ActorRef}
import com.ergodicity.core.SessionsState
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack, CurrentState}
import com.ergodicity.core.Sessions.{OngoingSessionTransition, CurrentOngoingSession, SubscribeOngoingSessions}
import com.ergodicity.core.common.{OptionContract, FutureContract, Security}

case class TrackInstrument[S <: Security](security: S)

class InstrumentTracking {
  self: TradingEngine =>

}

sealed trait InstrumentTrackerState

object InstrumentTrackerState {

  case object BindingSessions extends InstrumentTrackerState

  case object WaitingOngoingSession extends InstrumentTrackerState

  case object TrackingSession extends InstrumentTrackerState

}

sealed trait InstrumentTrackerData

object InstrumentTrackerData {

  case object Blank extends InstrumentTrackerData

  case class TrackedSession(session: ActorRef) extends InstrumentTrackerData

}




class InstrumentTracker(Sessions: ActorRef) extends Actor with FSM[InstrumentTrackerState, InstrumentTrackerData] {

  import InstrumentTrackerState._
  import InstrumentTrackerData._

  var trackingFutures: List[FutureContract] = Nil
  var trackingOptions: List[OptionContract] = Nil

  Sessions ! SubscribeTransitionCallBack(self)

  startWith(BindingSessions, Blank)

  when(BindingSessions) {
    case Event(CurrentState(Sessions, SessionsState.Online), Blank) =>
      goto(WaitingOngoingSession)

    case Event(Transition(Sessions, _, SessionsState.Online), Blank) =>
      goto(WaitingOngoingSession)
  }

  when(WaitingOngoingSession) {
    case Event(CurrentOngoingSession(Some(session)), Blank) =>
      goto(TrackingSession) using TrackedSession(session)

    case Event(OngoingSessionTransition(Some(session)), Blank) =>
      goto(TrackingSession) using TrackedSession(session)
  }

  when(TrackingSession) {
    case Event(OngoingSessionTransition(None), TrackedSession(old)) =>
      log.debug("Lost ongoing session; Old = " + old)
      goto(WaitingOngoingSession) using Blank

    case Event(OngoingSessionTransition(Some(session)), TrackedSession(old)) =>
      log.debug("Switch to new ongoing session: " + session + "; Old = " + old)
      stay() using TrackedSession(session)

  }

  onTransition {
    case BindingSessions -> WaitingOngoingSession =>
      log.debug("Subscribe for obgoing sessions")
      Sessions ! SubscribeOngoingSessions(self)
  }
}