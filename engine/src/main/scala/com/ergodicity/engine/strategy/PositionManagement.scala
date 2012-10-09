package com.ergodicity.engine.strategy

import akka.actor.{Props, ActorRef, FSM, Actor}
import akka.pattern.ask
import akka.pattern.pipe
import com.ergodicity.engine.service.Trading
import akka.util.duration._
import com.ergodicity.core.{OrderDirection, position, Isin, Security}
import com.ergodicity.engine.strategy.PositionManagerState.{Balancing, Balanced, CatchingInstrument}
import com.ergodicity.core.session.{InstrumentState, InstrumentParameters}
import com.ergodicity.engine.strategy.PositionManagerData.{ManagedPosition, UnderlyingInstrument, CatchingData}
import com.ergodicity.engine.strategy.InstrumentWatchDog.{WatchDogConfig, CatchedParameters, CatchedState, Catched}
import com.ergodicity.engine.strategy.PositionManagement.{AcquirePosition, PositionManagerStarted, PositionBalanced}
import com.ergodicity.engine.service.Trading.{Buy, Sell, OrderExecution}
import com.ergodicity.core.session.InstrumentParameters.{OptionParameters, FutureParameters}
import collection.mutable
import position.Position
import com.ergodicity.core.order.OrderActor.OrderEvent
import com.ergodicity.core.order.{Fill, Create, Cancel, Order}
import akka.util.Timeout

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

  def managePosition(isin: Isin, initialPosition: Position = Position.flat)(implicit config: PositionManagementConfig) = {
    if (positionManagers contains isin)
      throw new PositionManagementException("Position manager for isin = " + isin + " already registered")

    val creator = Props(new PositionManagerActor(trading, isin, initialPosition))
    val positionManagerActor = context.actorOf(creator, "PositionManager-" + isin.toActorName)
    positionManagers(isin) = positionManagerActor

    new PositionManager(positionManagerActor)
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

  case class ManagedPosition(instrument: UnderlyingInstrument, actual: Position, target: Position) extends PositionManagerData

}

class PositionManager(ref: ActorRef) {
  def acquire(position: Position) {
    ref ! AcquirePosition(position)
  }
}

class PositionManagerActor(trading: ActorRef, isin: Isin, initialPosition: Position)
                          (implicit config: PositionManagementConfig, watcher: InstrumentWatcher) extends Actor with FSM[PositionManagerState, PositionManagerData] {

  implicit val timeout = Timeout(5.seconds)

  implicit val watchDogConfig = WatchDogConfig(self, notifyOnCatched = true, notifyOnParams = true, notifyOnState = true)

  // Order executions
  val orders = mutable.Map[Order, OrderExecution]()

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

    case Event(StateTimeout, _) => failed("Failed to catch instrument for isin = " + isin)
  }

  when(Balanced) {
    case Event(AcquirePosition(acquired), managed@ManagedPosition(instrument, actual, target)) if(acquired != actual) =>
      balance(acquired - actual)
      goto(Balancing) using managed.copy(target = acquired)

    case Event(AcquirePosition(acquired), managed@ManagedPosition(instrument, actual, target)) if(acquired == actual) =>
    stay()
  }

  when(Balancing) {
    case Event(AcquirePosition(acquired), managed@ManagedPosition(instrument, actual, target)) =>
      balance(acquired - target)
      stay() using managed.copy(target = acquired)

    case Event(OrderEvent(order, Fill(amount, rest, deal)), managed@ManagedPosition(instrument, actual, target)) =>
      log.info("Order filled; Deal = " + deal)
      val afterFill = actual.pos + (order.direction match {
        case OrderDirection.Buy => amount
        case OrderDirection.Sell => -1 * amount
      })

      if (Position(afterFill) == target) {
        val newPosition = Position(afterFill)
        config.reportTo ! PositionBalanced(isin, newPosition)
        goto(Balanced) using managed.copy(actual = newPosition)
      } else {
        stay() using managed.copy(actual = Position(afterFill))
      }
  }

  whenUnhandled {
    // Updated catched instrument
    case Event(CatchedState(i, state), mp@ManagedPosition(instrument, actual, target)) if (i == isin) =>
      stay() using ManagedPosition(instrument.copy(state = state), actual, target)

    case Event(CatchedParameters(i, params), mp@ManagedPosition(instrument, actual, target)) if (i == isin) =>
      stay() using ManagedPosition(instrument.copy(parameters = params), actual, target)

    // Track orders
    case Event(execution: OrderExecution, _) =>
      orders(execution.order) = execution
      execution.subscribeOrderEvents(self)
      stay()

    // Handle Order Events
    case Event(OrderEvent(order, Create(_)), _) =>
      stay()

    case Event(OrderEvent(order, Cancel(amount)), _) if (orders contains order) && (amount > 0) =>
      log.warning("Order cancelled with amount > 0; amount = " + amount + ", order = " + order)
      orders -= order
      stay()

    case Event(OrderEvent(order, Cancel(amount)), _) if (orders contains order) && (amount == 0) =>
      orders -= order
      stay()
  }

  initialize

  private def balance(diff: Position) {
    def sellPrice(parameters: InstrumentParameters) = parameters match {
      case FutureParameters(lastClQuote, limits) => lastClQuote - limits.lower
      case OptionParameters(lastClQuote) => failed("Option parameters no supported")
    }

    def buyPrice(parameters: InstrumentParameters) = parameters match {
      case FutureParameters(lastClQuote, limits) => lastClQuote + limits.upper
      case OptionParameters(lastClQuote) => failed("Option parameters no supported")
    }

    val underlying = stateData.asInstanceOf[ManagedPosition].instrument

    diff.dir match {
      case position.Long =>
        (trading ? Buy(underlying.security, diff.pos.abs, buyPrice(underlying.parameters))) pipeTo self

      case position.Short =>
        (trading ? Sell(underlying.security, diff.pos.abs, sellPrice(underlying.parameters))) pipeTo self

      case position.Flat => // do nothing
    }
  }

  private def failed(message: String): Nothing = {
    throw new PositionManagementException(message)
  }

  private def catchUp(catching: CatchingData) = catching match {
    case CatchingData(Some(sec), Some(parameters), Some(state)) =>
      val underlying = UnderlyingInstrument(sec, parameters, state)
      log.debug("Ready to trade; Underlying instrument = " + underlying)

      // Report on position
      config.reportTo ! PositionManagerStarted(isin, sec, initialPosition)
      config.reportTo ! PositionBalanced(isin, initialPosition)

      // Ang go to Balanced state
      goto(Balanced) using ManagedPosition(underlying, initialPosition, initialPosition)

    case _ => stay() using catching
  }

  onTransition {
    case _ -> Balanced => log.info("Position balanced")
    case _ -> Balancing => log.info("Balancing position")
  }
}