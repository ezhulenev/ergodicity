package com.ergodicity.cep.computation

import akka.actor.ActorRef

object Spout {

}

case class Compute(payload: Any)
case class ComputationOutput(spout: ActorRef, value: Option[Any])
case class SubscribeComputation(ref: ActorRef)


