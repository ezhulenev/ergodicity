package com.ergodicity.core.position

import akka.actor.{ActorRef, FSM, Actor}
import com.ergodicity.core.IsinId


sealed trait PositionState

object PositionState {

  case object ClosedPosition extends PositionState

  case object OpenedPosition extends PositionState

}

object Position {

  case class Data(open: Int = 0, buys: Int = 0, sells: Int = 0, position: Int = 0, volume: BigDecimal = 0, lastDealId: Option[Long] = None)

  case class UpdatePosition(data: Position.Data)

  case class SubscribePositionUpdates(ref: ActorRef)

  case class CurrentPosition(position: ActorRef, data: Position.Data)

  case class PositionUpdated(position: ActorRef, from: Position.Data, to: Position.Data)

}

class Position(isin: IsinId) extends Actor with FSM[PositionState, Position.Data] {

  import Position._
  import PositionState._

  private var subscribers = List[ActorRef]()

  startWith(ClosedPosition, Data())

  when(ClosedPosition)(handleUpdatePosition)

  when(OpenedPosition)(handleUpdatePosition)

  private def handleUpdatePosition: StateFunction = {
    case Event(UpdatePosition(to), from) if (from != to) =>
      subscribers.foreach(_ ! PositionUpdated(self, from, to))
      goto(nexState(to)) using to
  }

  def nexState(data: Data) = if (data.position == 0) ClosedPosition else OpenedPosition

  whenUnhandled {
    case Event(SubscribePositionUpdates(ref), current) =>
      subscribers = ref :: subscribers
      ref ! CurrentPosition(self, current)
      stay()
  }

  initialize
}