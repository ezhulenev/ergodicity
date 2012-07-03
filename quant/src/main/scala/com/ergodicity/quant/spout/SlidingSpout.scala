package com.ergodicity.quant.spout

import com.ergodicity.quant.MarketEvent
import com.ergodicity.quant.computation.SlidingComputation
import akka.event.Logging
import akka.actor.{ActorRef, Actor}
import com.ergodicity.quant.computation.SlidingReaction.{Slide, Stay}
import scalaz.NonEmptyList

sealed trait SlidingState

case class SubscribeSlideOut(ref: ActorRef)

class SlidingSpout[E <: MarketEvent, C](slidingComputation: SlidingComputation[E, C]) extends Actor {
  val log = Logging(context.system, self)

  private var computation = slidingComputation

  private var slideOutSubscribers: Seq[ActorRef] = Seq()
  private var subscribers: Seq[ActorRef] = Seq()

  private def handleSubscribeEvents: Receive = {
    case SubscribeSlideOut(ref) => slideOutSubscribers = ref +: slideOutSubscribers
    case SubscribeComputation(ref) => subscribers = ref +: subscribers
  }

  private def handleComputeEvent: Receive = {
    case Compute(event: E) =>
      computation = computation(event) match {
        case Stay(c) => c

        case Slide(slideOut, c) =>
          notifySlideOut(slideOut)
          c
      }

      notifySubscribers()
  }

  protected def receive = handleSubscribeEvents orElse handleComputeEvent

  private def notifySubscribers() {
    lazy val value = computation()
    subscribers.foreach(_ ! ComputationOutput(self, value))
  }
  
  private def notifySlideOut(slideOut: NonEmptyList[E]) {
    slideOutSubscribers.foreach(s => {
      slideOut.map(s ! _)
    })
  }
}