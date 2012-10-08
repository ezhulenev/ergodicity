package com.ergodicity.engine.strategy

import akka.actor.{Props, ActorRef, FSM, Actor}
import com.ergodicity.engine.service.Trading
import com.ergodicity.core.position.Position
import akka.util.duration._
import com.ergodicity.core.{Isin, Security}
import com.ergodicity.engine.strategy.PositionManagerState.{Balanced, CatchingInstrument}
import collection.mutable
import com.ergodicity.core.session.{InstrumentState, InstrumentParameters}
import com.ergodicity.engine.strategy.PositionManagerData.{ManagedPosition, UnderlyingInstrument, CatchingData}
import com.ergodicity.engine.strategy.InstrumentWatchDog.{WatchDogConfig, CatchedParameters, CatchedState, Catched}
import com.ergodicity.engine.strategy.PositionManagement.{AcquirePosition, PositionManagerStarted, PositionBalanced}

class PositionManagementException(msg: String) extends RuntimeException(msg)

case class PositionManagementConfig(reportTo: ActorRef)

object PositionManagement {

  case class PositionManagerStarted(isin: Isin, security: Security, position: Position)

  case class PositionBalanced(isin: Isin, position: Position)

  case class AcquirePosition(target: Position)

}

trait PositionManagement {
  strategy: Strategy with InstrumentWatcher with Actor =>

  private[this] implicit val watcher: InstrumentWatcher = strategy

  private[this] val trading = engine.services.service(Trading.Trading)

  private[this] val positionManagers = mutable.Map[Isin, ActorRef]()

  def managePosition(isin: Isin, initialPosition: Position = Position.flat)(implicit config: PositionManagementConfig) {
    if (positionManagers contains isin)
      throw new PositionManagementException("Position manager for isin = " + isin + " already registered")

    val creator = Props(new PositionManagerActor(trading, isin, initialPosition))
    val positionManagerActor = context.actorOf(creator, "PositionManager-" + isin.toActorName)
    positionManagers(isin) = positionManagerActor
  }
}

sealed trait PositionManagerState

object PositionManagerState {

  case object CatchingInstrument extends PositionManagerState

  case object Balanced extends PositionManagerState

  case object Balancing extends PositionManagerState

  case class Positions(target: Position, actual: Position)

}

sealed trait PositionManagerData

object PositionManagerData {

  case class CatchingData(security: Option[Security], parameters: Option[InstrumentParameters], state: Option[InstrumentState]) extends PositionManagerData

  case class UnderlyingInstrument(security: Security, parameters: InstrumentParameters, state: InstrumentState)

  case class ManagedPosition(instrument: UnderlyingInstrument, position: Position) extends PositionManagerData

}

class PositionManager(ref: ActorRef) {
  def acquire(position: Position) {
    ref ! AcquirePosition(position)
  }
}

class PositionManagerActor(trading: ActorRef, isin: Isin, initialPosition: Position)
                          (implicit config: PositionManagementConfig, watcher: InstrumentWatcher) extends Actor with FSM[PositionManagerState, PositionManagerData] {

  implicit val watchDogConfig = WatchDogConfig(self, notifyOnCatched = true, notifyOnParams = true, notifyOnState = true)

  override def preStart() {
    log.info("Start position manager for isin = " + isin + ", initial position = " + initialPosition)
    // Watch for managed instrument
    watcher.watchInstrument(isin)
  }

  startWith(CatchingInstrument, CatchingData(None, None, None))

  when(CatchingInstrument, stateTimeout = 30.seconds) {
    case Event(Catched(i, instrument), catching: CatchingData) if (i == isin) =>
      log.info("Catched assigned instrument; Isin = {}, session = {}, security = {}", isin, instrument.session, instrument.security)
      catchUp(catching.copy(security = Some(instrument.security)))

    case Event(CatchedState(i, state), catching: CatchingData) if (i == isin) =>
      catchUp(catching.copy(state = Some(state)))

    case Event(CatchedParameters(i, params), catching: CatchingData) if (i == isin) =>
      catchUp(catching.copy(parameters = Some(params)))

    case Event(StateTimeout, _) => throw new PositionManagementException("Failed to catch instrument for isin = " + isin)
  }

  when(Balanced) {
    case Event(_, _) => stay()
  }

  whenUnhandled {
    case Event(CatchedState(i, state), mp@ManagedPosition(instrument, pos)) if (i == isin) =>
      stay() using ManagedPosition(instrument.copy(state = state), pos)

    case Event(CatchedParameters(i, params), mp@ManagedPosition(instrument, pos)) if (i == isin) =>
      stay() using ManagedPosition(instrument.copy(parameters = params), pos)
  }


  initialize

  private def catchUp(catching: CatchingData) = catching match {
    case CatchingData(Some(sec), Some(parameters), Some(state)) =>
      val underlying = UnderlyingInstrument(sec, parameters, state)
      log.debug("Ready to trade; Underlying instrument = " + underlying)
      config.reportTo ! PositionManagerStarted(isin, sec, initialPosition)
      goto(Balanced) using ManagedPosition(underlying, initialPosition)

    case _ => stay() using catching
  }

  onTransition {
    case _ -> Balanced => config.reportTo ! PositionBalanced(isin, stateData.asInstanceOf[ManagedPosition].position)
  }
}