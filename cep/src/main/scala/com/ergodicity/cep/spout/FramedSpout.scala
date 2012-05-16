package com.ergodicity.cep.spout

import akka.actor.{ActorRef, Actor}
import com.ergodicity.cep.MarketEvent
import com.ergodicity.cep.computation.FramedReaction.{Jump, Stay}
import com.ergodicity.cep.computation.FramedComputation

case class SubscribeIntermediateComputations(ref: ActorRef)

case class IntermediateComputation[C](computation: ActorRef, value: C)

class FramedSpout[E <: MarketEvent, C](initialComputation: FramedComputation[E, C]) extends Actor {

  private var intermediateSubscribers: Seq[ActorRef] = Seq()
  private var subscribers: Seq[ActorRef] = Seq()

  private var computation = initialComputation

  private def handleSubscribeEvents: Receive = {
    case SubscribeIntermediateComputations(ref) => intermediateSubscribers = ref +: intermediateSubscribers
    case SubscribeComputation(ref) => subscribers = ref +: subscribers
  }

  private def handleComputeEvent: Receive = {
    case Compute(event: E) =>
      computation = computation(event) match {
        case Stay(c) => c
        case Jump(slideOut, c) => slideOut.map(notifyCompletedSubscribers(_)); c
      }

      notifyIntermediateSubscribers()
  }


  protected def receive = handleSubscribeEvents orElse handleComputeEvent

  private def notifyIntermediateSubscribers() {
    lazy val value = computation()
    intermediateSubscribers foreach {
      _ ! IntermediateComputation(self, value)
    }
  }

  private def notifyCompletedSubscribers(computation: FramedComputation[E, C]) {
    lazy val value = computation()
    subscribers foreach {
      _ ! ComputationOutput(self, value)
    }
  }
}