package com.ergodicity.core.position

import akka.actor.{ActorRef, FSM, Actor}
import com.ergodicity.core.IsinId


sealed trait PositionState

object PositionState {

  case object Long extends PositionState

  case object Short extends PositionState

  case object Flat extends PositionState
}

object Position {

  case class Data(open: Int = 0, buys: Int = 0, sells: Int = 0, position: Int = 0, volume: BigDecimal = 0, lastDealId: Option[Long] = None) {
    import PositionState._

    def state: PositionState = if (position == 0) Flat else if (position > 0) Long else Short
  }

  case class UpdatePosition(data: Position.Data)

  case class SubscribePositionUpdates(ref: ActorRef)

  case class CurrentPosition(position: ActorRef, data: Position.Data)

  case class PositionUpdated(position: ActorRef, from: Position.Data, to: Position.Data)

}

class Position(isin: IsinId) extends Actor with FSM[PositionState, Position.Data] {

  import Position._
  import PositionState._

  private var subscribers = List[ActorRef]()

  startWith(Flat, Data())

  when(Flat)(handleUpdatePosition)

  when(Long)(handleUpdatePosition)

  when(Short)(handleUpdatePosition)


  private def handleUpdatePosition: StateFunction = {
    case Event(UpdatePosition(to), from) if (from != to) =>
      subscribers.foreach(_ ! PositionUpdated(self, from, to))
      goto(to.state) using to
  }

  whenUnhandled {
    case Event(SubscribePositionUpdates(ref), current) =>
      subscribers = ref :: subscribers
      ref ! CurrentPosition(self, current)
      stay()
  }

  initialize
}