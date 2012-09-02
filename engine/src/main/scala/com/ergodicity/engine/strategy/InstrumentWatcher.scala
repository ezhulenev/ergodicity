package com.ergodicity.engine.strategy

import com.ergodicity.engine.service.InstrumentData.{InstrumentData => InstrumentDataId}
import akka.dispatch.Await
import akka.util.duration._
import akka.pattern.ask
import akka.pattern.pipe
import akka.actor._
import com.ergodicity.core.Isin
import com.ergodicity.engine.strategy.InstrumentWatchDogState.{Watching, Catching}
import com.ergodicity.core.session.Instrument
import com.ergodicity.core.SessionsTracking.SubscribeOngoingSessions
import com.ergodicity.core.session.SessionActor.{AssignedInstruments, GetAssignedInstruments, GetInstrumentActor}
import com.ergodicity.core.SessionsTracking.OngoingSessionTransition
import scala.Some
import com.ergodicity.core.SessionsTracking.OngoingSession
import akka.util.Timeout
import com.ergodicity.engine.strategy.InstrumentWatchDog.Catched

trait InstrumentWatcher {
  strategy: Strategy =>

  val instrumentData = Await.result(services.service(InstrumentDataId).map(_.ref), 1.second)

}

class InstrumentWatcherException(message: String) extends RuntimeException(message)


sealed trait InstrumentWatchDogState

object InstrumentWatchDogState {

  case object Catching extends InstrumentWatchDogState

  case object Watching extends InstrumentWatchDogState

  case object Switching extends InstrumentWatchDogState

}

object InstrumentWatchDog {
  case class Catched(isin: Isin, instrument: Instrument, ref: ActorRef)
}


class InstrumentWatchDog(isin: Isin, reportTo: ActorRef, instrumentData: ActorRef) extends Actor with LoggingFSM[InstrumentWatchDogState, Option[Catched]] {

  implicit val timeout = Timeout(1.second)

  override def preStart() {
    instrumentData ! SubscribeOngoingSessions(self)
  }

  startWith(Catching, None)

  when(Catching, stateTimeout = 1.second) {
    case Event(OngoingSession(Some((id, session))), _) =>
      log.info("Catched ongoing session, session id = " + id)
      val actor = (session ? GetInstrumentActor(isin)).mapTo[ActorRef]
      val instrument = (session ? GetAssignedInstruments).mapTo[AssignedInstruments] map (_.instruments.find(_.security.isin == isin))

      (actor zip instrument).map {
        case (ref, Some(i)) => Catched(isin, i, ref)
        case _ => throw new InstrumentWatcherException("Can't find instrument matching isin = " + isin)
      } pipeTo self

      stay()

    case Event(catched: Catched, _) =>
      log.info("Catched instrument for isin = " + isin + "; catched = " + catched)
      reportTo ! catched
      goto(Watching) using Some(catched)

    case Event(FSM.StateTimeout, _) =>
      log.error("Catching instrument for isin = {} timed out", isin)
      throw new InstrumentWatcherException("Catching instrument for isin = " + isin + " timed out")
  }

  when(Watching) {
    case Event(OngoingSessionTransition(_, Some((id, session))), _) =>
      log.info("Catched ongoing session transition, new session id = " + id)
      val actor = (session ? GetInstrumentActor(isin)).mapTo[ActorRef]
      val instrument = (session ? GetAssignedInstruments).mapTo[AssignedInstruments].map(_.instruments.find(_.security.isin == isin))

      (actor zip instrument).map {
        case (ref, Some(i)) => Catched(isin, i, ref)
        case _ => throw new IllegalStateException("Can't find instrument matching isin = " + isin)
      } pipeTo self

      goto(Catching)
  }

  whenUnhandled {
    case Event(Status.Failure(cause), _) =>
      log.error("WatchDog failed, cause = {}", cause)
      throw new InstrumentWatcherException("WatchDog failed, cause = " + cause)

    case Event(OngoingSession(None), _) =>
      log.error("No ongoing session")
      throw new InstrumentWatcherException("No ongoing session")

    case Event(OngoingSessionTransition(_, None), _) =>
      log.error("Lost ongoing session")
      throw new InstrumentWatcherException("Lost ongoing session")

    case Event(Terminated(ref), Some(Catched(_, _, catched))) if (ref == catched) =>
      log.error("catched instrument unexpectedly terminated")
      throw new InstrumentWatcherException("Watched instrument unexpectedly terminated")
  }

  initialize
}
