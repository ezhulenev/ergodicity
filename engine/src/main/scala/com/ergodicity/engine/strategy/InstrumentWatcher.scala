package com.ergodicity.engine.strategy

import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.FSM.UnsubscribeTransitionCallBack
import akka.actor._
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.core.{Isin, SessionId, Security}
import com.ergodicity.core.SessionsTracking.OngoingSession
import com.ergodicity.core.SessionsTracking.OngoingSessionTransition
import com.ergodicity.core.SessionsTracking.SubscribeOngoingSessions
import com.ergodicity.core.session.{InstrumentParameters, InstrumentUpdated, InstrumentState}
import com.ergodicity.core.session.InstrumentActor.SubscribeInstrumentCallback
import com.ergodicity.core.session.InstrumentActor.UnsubscribeInstrumentCallback
import com.ergodicity.core.session.SessionActor.{InstrumentRef, AssignedContents, GetAssignedContents, GetInstrument}
import com.ergodicity.engine.service.InstrumentData.{InstrumentData => InstrumentDataId}
import com.ergodicity.engine.strategy.InstrumentWatchDog._
import com.ergodicity.engine.strategy.InstrumentWatchDogState.{Watching, Catching}
import scala.Some

trait InstrumentWatcher {
  strategy: Strategy with Actor =>

  val instrumentData = engine.services.service(InstrumentDataId)

  def watchInstrument(isin: Isin)(implicit config: WatchDogConfig) {
    context.actorOf(Props(new InstrumentWatchDog(isin, instrumentData)), "WatchDog-" + isin.toActorName)
  }

}

class InstrumentWatcherException(message: String) extends RuntimeException(message)


sealed trait InstrumentWatchDogState

object InstrumentWatchDogState {

  case object Catching extends InstrumentWatchDogState

  case object Watching extends InstrumentWatchDogState

  case object Switching extends InstrumentWatchDogState

}

object InstrumentWatchDog {

  case class Catched(security: Security, session: SessionId, instrument: InstrumentRef)

  case class CatchedState(security: Security, state: InstrumentState)

  case class CatchedParameters(security: Security, parameters: InstrumentParameters)

  case class WatchDogConfig(reportTo: ActorRef, notifyOnCatched: Boolean = true, notifyOnState: Boolean = false, notifyOnParams: Boolean = false)
}


class InstrumentWatchDog(isin: Isin, instrumentData: ActorRef)(implicit config: WatchDogConfig) extends Actor with LoggingFSM[InstrumentWatchDogState, Option[InstrumentRef]] {

  import config._

  implicit val timeout = Timeout(1.second)

  override def preStart() {
    instrumentData ! SubscribeOngoingSessions(self)
  }

  startWith(Catching, None)

  private def joinSession(id: SessionId, session: ActorRef) {
    val assigned = (session ? GetAssignedContents).mapTo[AssignedContents]
    val sec = assigned.map(_ ? isin getOrElse(throw new InstrumentWatcherException("Can't find security for isin = " + isin + ", assigned to session = " + id)))
    val instrument = sec.flatMap(s => (session ? GetInstrument(s)).mapTo[InstrumentRef])
    instrument pipeTo self
  }

  when(Catching, stateTimeout = 1.second) {
    case Event(OngoingSession(id, session), _) =>
      log.info("Catched ongoing session, session id = " + id)
      joinSession(id, session)
      stay()

    case Event(ref@InstrumentRef(session, s, instrumentActor), outdated) if (s.isin == isin) =>
      log.info("Catched instrument for isin = " + isin + "; ref = " + ref)

      // Unwatch outdated instrument
      outdated.foreach(old => context.unwatch(old.instrumentActor))
      outdated.foreach(_.instrumentActor ! UnsubscribeTransitionCallBack(self))
      outdated.foreach(_.instrumentActor ! UnsubscribeInstrumentCallback(self))

      // Watch for catched instrument
      context.watch(ref.instrumentActor)
      ref.instrumentActor ! SubscribeTransitionCallBack(self)
      ref.instrumentActor ! SubscribeInstrumentCallback(self)

      onCatched(ref)

      goto(Watching) using Some(ref)

    case Event(FSM.StateTimeout, _) =>
      log.error("Catching instrument for isin = {} timed out", isin)
      throw new InstrumentWatcherException("Catching instrument for isin = " + isin + " timed out")
  }

  when(Watching) {
    case Event(OngoingSessionTransition(_, OngoingSession(id, session)), _) =>
      log.info("Catched ongoing session transition, new session id = " + id)
      joinSession(id, session)
      goto(Catching)
  }

  whenUnhandled {
    case Event(Status.Failure(cause), _) =>
      log.error("WatchDog failed, cause = {}", cause)
      throw new InstrumentWatcherException("WatchDog failed, cause = " + cause)

    case Event(Terminated(ref), Some(InstrumentRef(_, _, catched))) if (ref == catched) =>
      throw new InstrumentWatcherException("Watched instrument unexpectedly terminated")

    case Event(CurrentState(ref, state: InstrumentState), Some(InstrumentRef(_, security, catched))) if (ref == catched) =>
      onStateCatched(security, state)
      stay()

    case Event(Transition(ref, _, state: InstrumentState), Some(InstrumentRef(_, security, catched))) if (ref == catched) =>
      onStateCatched(security, state)
      stay()

    case Event(instrument@InstrumentUpdated(ref, parameters), Some(InstrumentRef(_, security, catched))) if (ref == catched) =>
      onParametersCatched(security, parameters)
      stay()
  }

  initialize

  private def onCatched(instrument: InstrumentRef) {
    if (notifyOnCatched) reportTo ! Catched(instrument.security, instrument.session, instrument)
  }

  private def onStateCatched(security: Security, state: InstrumentState) {
    if (notifyOnState) reportTo ! CatchedState(security, state)
  }

  private def onParametersCatched(security: Security, parameters: InstrumentParameters) {
    if (notifyOnParams) reportTo ! CatchedParameters(security, parameters)
  }
}
