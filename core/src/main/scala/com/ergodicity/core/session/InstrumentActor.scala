package com.ergodicity.core.session

import akka.actor.{ActorRef, FSM, LoggingFSM, Actor}
import akka.util.duration._
import com.ergodicity.core.{OptionContract, FutureContract, Security}
import com.ergodicity.core.session.InstrumentParameters.{OptionParameters, FutureParameters}

sealed trait InstrumentState

object InstrumentState {

  def apply(sessionState: SessionState) = sessionState match {
    case SessionState.Assigned => Assigned
    case SessionState.Online => Online
    case SessionState.Suspended => Suspended
    case SessionState.Canceled => Canceled
    case SessionState.Completed => Completed
  }

  def apply(state: Long) = state match {
    case 0 => Assigned
    case 1 => Online
    case 2 => Suspended
    case 3 => Canceled
    case 4 => Completed
    case 5 => Suspended
  }

  case object Assigned extends InstrumentState

  case object Online extends InstrumentState

  case object Canceled extends InstrumentState

  case object Completed extends InstrumentState

  case object Suspended extends InstrumentState

}

object InstrumentActor {

  case class IllegalLifeCycleEvent(msg: String, event: Any) extends IllegalArgumentException

  case class SubscribeInstrumentCallback(ref: ActorRef)

  case class UnsubscribeInstrumentCallback(ref: ActorRef)

}

sealed trait InstrumentParameters

object InstrumentParameters {

  case class Limits(lower: BigDecimal, upper: BigDecimal)

  case class FutureParameters(lastClQuote: BigDecimal, limits: Limits) extends InstrumentParameters

  case class OptionParameters(lastClQuote: BigDecimal) extends InstrumentParameters

}

case class InstrumentUpdated(actor: ActorRef, parameters: InstrumentParameters)

class FutureInstrument(security: FutureContract) extends InstrumentActor(security) with FutureParametersHandling

class OptionInstrument(security: OptionContract) extends InstrumentActor(security) with OptionParametersHandling

private[session] abstract class InstrumentActor[S <: Security](security: S) extends Actor with LoggingFSM[InstrumentState, Option[InstrumentParameters]] {

  import InstrumentActor._
  import InstrumentState._

  private var subscribers: Seq[ActorRef] = Seq()

  override def preStart() {
    log.info("Started instrument for security = " + security)
    super.preStart()
  }

  // Start in suspended state
  startWith(Suspended, None, timeout = Some(1.second))

  when(Assigned) {
    handleInstrumentState orElse handleInstrumentParameters
  }

  when(Online) {
    handleInstrumentState orElse handleInstrumentParameters
  }

  when(Canceled) {
    case Event(Canceled, _) => stay()
    case Event(e, _) =>
      throw new IllegalLifeCycleEvent("Unexpected event after cancellation", e)
  }

  when(Completed) {
    case Event(Completed, _) => stay()
    case Event(e, _) =>
      throw new IllegalLifeCycleEvent("Unexpected event after completion", e)
  }

  when(Suspended) {
    handleInstrumentState orElse handleInstrumentParameters
  }

  whenUnhandled {
    case Event(e@FSM.StateTimeout, _) =>
      throw new IllegalLifeCycleEvent("Timed out in initial Suspended state", e)

    case Event(SubscribeInstrumentCallback(ref), None) =>
      subscribers = subscribers :+ ref
      stay()

    case Event(SubscribeInstrumentCallback(ref), Some(params)) =>
      ref ! InstrumentUpdated(self, params)
      subscribers = subscribers :+ ref
      stay()

    case Event(UnsubscribeInstrumentCallback(ref), _) =>
      subscribers = subscribers filterNot (_ == ref)
      stay()
  }

  initialize

  private def handleInstrumentState: StateFunction = {
    case Event(state: InstrumentState, _) => goto(state)
  }

  protected def handleInstrumentParameters: StateFunction

  protected def notifySubscribers(params: InstrumentParameters) {
    subscribers foreach (_ ! InstrumentUpdated(self, params))
  }
}

private[session] trait FutureParametersHandling {
  self: InstrumentActor[FutureContract] =>
  protected def handleInstrumentParameters: StateFunction = {
    case Event(params@FutureParameters(_, _), None) =>
      notifySubscribers(params)
      stay() using Some(params)

    case Event(params@FutureParameters(_, _), Some(old)) if (old != params) =>
      notifySubscribers(params)
      stay() using Some(params)
  }
}

private[session] trait OptionParametersHandling {
  self: InstrumentActor[OptionContract] =>
  protected def handleInstrumentParameters: StateFunction = {
    case Event(params@OptionParameters(_), None) =>
      notifySubscribers(params)
      stay() using Some(params)

    case Event(params@OptionParameters(_), Some(old)) if (old != params) =>
      notifySubscribers(params)
      stay() using Some(params)
  }
}
