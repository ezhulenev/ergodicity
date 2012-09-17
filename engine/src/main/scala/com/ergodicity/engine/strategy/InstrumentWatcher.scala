package com.ergodicity.engine.strategy

import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.FSM.UnsubscribeTransitionCallBack
import akka.actor.Terminated
import akka.actor._
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.core.SessionId
import com.ergodicity.core.SessionsTracking.OngoingSession
import com.ergodicity.core.SessionsTracking.OngoingSessionTransition
import com.ergodicity.core.SessionsTracking.SubscribeOngoingSessions
import com.ergodicity.core.session.Instrument
import com.ergodicity.core.session.InstrumentActor.SubscribeInstrumentCallback
import com.ergodicity.core.session.InstrumentActor.UnsubscribeInstrumentCallback
import com.ergodicity.core.session.InstrumentState
import com.ergodicity.core.session.SessionActor.AssignedContents
import com.ergodicity.core.session.SessionActor.GetAssignedContents
import com.ergodicity.core.session.SessionActor.GetInstrumentActor
import com.ergodicity.core.{Security, Isin}
import com.ergodicity.engine.service.InstrumentData.{InstrumentData => InstrumentDataId}
import com.ergodicity.engine.strategy.InstrumentWatchDog.Catched
import com.ergodicity.engine.strategy.InstrumentWatchDog.CatchedState
import com.ergodicity.engine.strategy.InstrumentWatchDog.WatchDogConfig
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

  case class Catched(isin: Isin, session: SessionId, security: Security, instrument: ActorRef)

  case class CatchedState(isin: Isin, state: InstrumentState)

  case class CatchedInstrument(isin: Isin, instrument: Instrument)

  case class WatchDogConfig(reportTo: ActorRef, notifyOnCatched: Boolean = true, notifyOnState: Boolean = false, notifyOnParams: Boolean = false)

}


class InstrumentWatchDog(isin: Isin, instrumentData: ActorRef)(implicit config: WatchDogConfig) extends Actor with LoggingFSM[InstrumentWatchDogState, Option[Catched]] {

  import config._

  implicit val timeout = Timeout(1.second)

  override def preStart() {
    instrumentData ! SubscribeOngoingSessions(self)
  }

  startWith(Catching, None)

  when(Catching, stateTimeout = 1.second) {
    case Event(OngoingSession(id, session), _) =>
      log.info("Catched ongoing session, session id = " + id)
      val actor = (session ? GetInstrumentActor(isin)).mapTo[ActorRef]
      val instrument = (session ? GetAssignedContents).mapTo[AssignedContents] map (_.contents.find(_.isin == isin))

      (actor zip instrument).map {
        case (ref, Some(i)) => Catched(isin, id, i, ref)
        case _ => throw new InstrumentWatcherException("Can't find instrument matching isin = " + isin)
      } pipeTo self

      stay()

    case Event(catched: Catched, outdated) =>
      log.info("Catched instrument for isin = " + isin + "; catched = " + catched)

      // Unwatch outdated instrument
      outdated.foreach(old => context.unwatch(old.instrument))
      outdated.foreach(_.instrument ! UnsubscribeTransitionCallBack(self))
      outdated.foreach(_.instrument ! UnsubscribeInstrumentCallback(self))

      // Watch for catched instrument
      context.watch(catched.instrument)
      catched.instrument ! SubscribeTransitionCallBack(self)
      catched.instrument ! SubscribeInstrumentCallback(self)

      onCatched(catched)

      goto(Watching) using Some(catched)

    case Event(FSM.StateTimeout, _) =>
      log.error("Catching instrument for isin = {} timed out", isin)
      throw new InstrumentWatcherException("Catching instrument for isin = " + isin + " timed out")
  }

  when(Watching) {
    case Event(OngoingSessionTransition(_, OngoingSession(id, session)), _) =>
      log.info("Catched ongoing session transition, new session id = " + id)
      val actor = (session ? GetInstrumentActor(isin)).mapTo[ActorRef]
      val instrument = (session ? GetAssignedContents).mapTo[AssignedContents].map(_.contents.find(_.isin == isin))

      (actor zip instrument).map {
        case (ref, Some(i)) => Catched(isin, id, i, ref)
        case _ => throw new IllegalStateException("Can't find instrument matching isin = " + isin)
      } pipeTo self

      goto(Catching)
  }

  whenUnhandled {
    case Event(Status.Failure(cause), _) =>
      log.error("WatchDog failed, cause = {}", cause)
      throw new InstrumentWatcherException("WatchDog failed, cause = " + cause)

    case Event(Terminated(ref), Some(Catched(_, _, _, catched))) if (ref == catched) =>
      throw new InstrumentWatcherException("Watched instrument unexpectedly terminated")

    case Event(CurrentState(ref, state: InstrumentState), Some(Catched(_, _, _, catched))) if (ref == catched) =>
      onStateCatched(state)
      stay()

    case Event(Transition(ref, _, state: InstrumentState), Some(Catched(_, _, _, catched))) if (ref == catched) =>
      onStateCatched(state)
      stay()

    case Event(instrument@Instrument(ref, _, _), Some(Catched(_, _, _, catched))) if (ref == catched)=>
      onInstrumentCatched(instrument)
      stay()
  }

  initialize

  private def onCatched(catched: Catched) {
    if (notifyOnCatched) reportTo ! catched
  }

  private def onStateCatched(state: InstrumentState) {
    if (notifyOnState) reportTo ! CatchedState(isin, state)
  }

  private def onInstrumentCatched(instrument: Instrument) {
    if (notifyOnParams) reportTo ! CatchedInstrument(isin, instrument)
  }
}
