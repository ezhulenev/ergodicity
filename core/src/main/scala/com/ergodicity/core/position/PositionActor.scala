package com.ergodicity.core.position

import akka.actor.{ActorLogging, ActorRef, Actor}
import com.ergodicity.core.IsinId
import com.ergodicity.cgate.WhenUnhandled


object PositionActor {

  case class UpdatePosition(position: Position, dynamics: PositionDynamics)

  case class SubscribePositionUpdates(ref: ActorRef)

  case class CurrentPosition(ref: ActorRef, position: Position)

  case class PositionTransition(ref: ActorRef, from: Position, to: Position)

}

class PositionActor(isin: IsinId) extends Actor with ActorLogging with WhenUnhandled {

  import PositionActor._

  var subscribers = List[ActorRef]()

  protected[position] var position: Position = Position.flat
  protected[position] var dynamics: PositionDynamics = PositionDynamics.empty

  protected def receive = handleUpdates orElse subscribe orElse whenUnhandled

  private def handleUpdates: Receive = {
    case UpdatePosition(to, d) if (to != d.aggregated) =>
      throw new IllegalStateException()

    case UpdatePosition(to, d) =>
      log.info("Position updated to " + to + ", dynamics = " + d)
      subscribers.foreach(_ ! PositionTransition(self, position, to))
      position = to
      dynamics = d
  }

  private def subscribe: Receive = {
    case SubscribePositionUpdates(ref) =>
      subscribers = ref :: subscribers
      ref ! CurrentPosition(self, position)
  }
}