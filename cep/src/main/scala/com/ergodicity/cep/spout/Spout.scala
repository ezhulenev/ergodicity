package com.ergodicity.cep.spout

import akka.actor.ActorRef

object Spout {

}

case class Compute(payload: Any)

case class ComputationOutput(spout: ActorRef, value: Any)

case class SubscribeComputation(ref: ActorRef)


