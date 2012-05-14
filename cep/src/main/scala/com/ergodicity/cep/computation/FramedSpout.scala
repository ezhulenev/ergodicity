package com.ergodicity.cep.computation

import akka.actor.{ActorRef, Actor}
import com.ergodicity.cep.Payload

case class SubscribeIntermediateComputations(ref: ActorRef)

case class IntermediateComputation[A](computation: ActorRef, value: Option[A])

class FramedSpout[A <: Payload, B](initialComputation: FramedComputation[A, B]) extends Actor {

  private var intermediateSubscribers: Seq[ActorRef] = Seq()
  private var subscribers: Seq[ActorRef] = Seq()

  private var computation = initialComputation

  private def handleSubscribeEvents: Receive = {
    case SubscribeIntermediateComputations(ref) => intermediateSubscribers = ref +: intermediateSubscribers
    case SubscribeComputation(ref) => subscribers = ref +: subscribers
  }

  private def handleComputeEvent: Receive = {
    case Compute(payload: A) =>
      val interval = computation.frame

      if (interval contains payload.time) {
        // Payload belongs to current interval
        computation = computation(payload)
        notifyIntermediateSubscribers()
      } else {
        // Current interval overflowed
        notifyCompletedSubscribers(computation)

        val stream = Stream.iterate(computation.sequent())(c => {
          notifyCompletedSubscribers(c)
          c.sequent()
        })

        // Find sequent interval
        val sequentComputation = stream.filter(_.frame contains payload.time).head

        // Apply current payload and notify intermediate listeners
        computation = sequentComputation(payload)
        notifyIntermediateSubscribers()
      }
  }


  protected def receive = handleSubscribeEvents orElse handleComputeEvent

  private def notifyIntermediateSubscribers() {
    lazy val value = computation()
    intermediateSubscribers foreach {
      _ ! IntermediateComputation(self, value)
    }
  }

  private def notifyCompletedSubscribers(computation: FramedComputation[A, B]) {
    lazy val value = computation()
    subscribers foreach {
      _ ! ComputationOutput(self, value)
    }
  }
}