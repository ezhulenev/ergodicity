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

trait InstrumentResolver {
  strategy: Strategy with Actor =>

  val instrumentData = engine.services.service(InstrumentDataId)

  def resolveInstrument(isin: Isin) {
    context.actorOf(Props(new Instr(security, instrumentData)), "WatchDog-" + security.isin.toActorName)
  }

}
