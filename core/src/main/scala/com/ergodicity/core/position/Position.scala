package com.ergodicity.core.position

import com.ergodicity.core.common.Isin
import akka.actor.{ActorRef, FSM, Actor}


sealed trait PositionState

case object UndefinedPosition extends PositionState

case object OpenedPosition extends PositionState


case class PositionData(open: Int, buys: Int, sells: Int, position: Int, volume: BigDecimal, lastDealId: Long)


// Events
case object TerminatedPosition

case class UpdatePosition(data: PositionData)

case class SubscribePositionUpdates(ref: ActorRef)

case object PositionOpened

case class PositionUpdated(position: ActorRef, data: PositionData)

case object PositionTerminated

class Position(isin: Isin) extends Actor with FSM[PositionState, Option[PositionData]] {

  private var subscribers = List[ActorRef]()

  log.debug("Create positioin for isin " + isin)

  startWith(UndefinedPosition, None)

  when(UndefinedPosition) {
    case Event(UpdatePosition(data), None) =>
      notifyOpened()
      notifyUpdated(data)
      goto(OpenedPosition) using Some(data)
  }

  when(OpenedPosition) {
    case Event(TerminatedPosition, _) =>
      notifyTerminated();
      goto(UndefinedPosition) using None

    case Event(UpdatePosition(data), Some(oldData)) =>
      notifyUpdated(data)
      stay() using Some(data)
  }

  whenUnhandled {
    case Event(SubscribePositionUpdates(ref), _) => subscribers = ref :: subscribers; stay()
  }

  initialize

  private def notifyTerminated() {
    subscribers.foreach(_ ! PositionTerminated)
  }
  
  private def notifyOpened() {
    subscribers.foreach(_ ! PositionOpened)
  }
  
  private def notifyUpdated(data: PositionData) {
    subscribers.foreach(_ ! PositionUpdated(self, data))
  }
}