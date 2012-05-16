package com.ergodicity.cep.spout

import com.ergodicity.cep.MarketEvent
import com.ergodicity.cep.computation.SlidingComputation
import akka.event.Logging
import akka.actor.{ActorRef, Actor}
import com.ergodicity.cep.computation.SlidingReaction.{Slide, Stay}

sealed trait SlidingState

class SlidingSpout[E <: MarketEvent, C](slidingComputation: SlidingComputation[E, C]) extends Actor {
  val log = Logging(context.system, self)

  private var computation = slidingComputation

  private var subscribers: Seq[ActorRef] = Seq()

  private def handleSubscribeEvents: Receive = {
    case SubscribeComputation(ref) => subscribers = ref +: subscribers
  }

  private def handleComputeEvent: Receive = {
    case Compute(event: E) =>
      computation = computation(event) match {
        case Stay(c) => c

        case Slide(slideOut, c) => c
      }

      notifySubscribers()
  }

  protected def receive = handleSubscribeEvents orElse handleComputeEvent

  private def notifySubscribers() {
    lazy val value = computation()
    subscribers.foreach(_ ! ComputationOutput(self, value))
  }
}