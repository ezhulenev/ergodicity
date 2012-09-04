package com.ergodicity.core.position

import akka.actor.{ActorLogging, ActorRef, Actor}
import com.ergodicity.core.Isin
import com.ergodicity.cgate.WhenUnhandled

object PositionActor {

  case class UpdatePosition(position: Position, dynamics: PositionDynamics)

  case object GetCurrentPosition

  case class SubscribePositionUpdates(ref: ActorRef)

  case class UnsubscribePositionUpdates(ref: ActorRef)

  case class CurrentPosition(isin: Isin, position: Position) {
    def tuple = (isin, position)
  }

  case class PositionTransition(isin: Isin, from: Position, to: Position)

}

class PositionActor(isin: Isin) extends Actor with ActorLogging with WhenUnhandled {

  import PositionActor._

  var subscribers = List[ActorRef]()

  protected[position] var position: Position = Position.flat
  protected[position] var dynamics: PositionDynamics = PositionDynamics.empty

  protected def receive = getPosition orElse handleUpdates orElse subscriptions orElse whenUnhandled

  private def handleUpdates: Receive = {
    case UpdatePosition(to, d) if (to != d.aggregated) =>
      throw new IllegalStateException()

    case UpdatePosition(to, d) =>
      log.info("Position updated to " + to + ", dynamics = " + d)
      subscribers.foreach(_ ! PositionTransition(isin, position, to))
      position = to
      dynamics = d
  }

  private def subscriptions: Receive = {
    case SubscribePositionUpdates(ref) =>
      subscribers = ref :: subscribers
      ref ! CurrentPosition(isin, position)

    case UnsubscribePositionUpdates(ref) =>
      subscribers = subscribers.filterNot(_ == ref)
  }

  private def getPosition: Receive = {
    case GetCurrentPosition => sender ! CurrentPosition(isin, position)
  }
}