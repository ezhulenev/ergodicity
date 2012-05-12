package com.ergodicity.cep.computation

import org.joda.time.Interval
import akka.actor.{ActorRef, FSM, Actor}

sealed trait ComputationResult[A]
case class TemporalComputation[A](computation: ActorRef, interval: Interval, value: A) extends ComputationResult[A]
case class FinalComputation[A](computation: ActorRef, interval: Interval, value: Option[A]) extends ComputationResult[A]

case class SubscribeComputation(ref: ActorRef)

sealed trait FramedComputationState
case object Idle extends FramedComputationState
case object Online extends FramedComputationState


case class SetUpFramedComputation[A, B](interval: Interval, computation: Computation[A, B])

class FramedComputation[A, B] extends Actor with FSM[FramedComputationState, Option[(Interval, Computation[A, B])]] {

  private var resultSubscribers: Seq[ActorRef] = Seq()

  startWith(Idle, None)

  when(Idle) {
    case Event(SubscribeComputation(ref), None) => resultSubscribers = ref +: resultSubscribers; stay()
    case Event(setUp : SetUpFramedComputation[A, B], None) => goto(Online) using Some((setUp.interval, setUp.computation))
  }

  when(Online) {
    case Event(payload: A, Some((interval, computation))) =>
      val cmp = computation(payload)
      val value = cmp()
      resultSubscribers foreach {_ ! TemporalComputation(self, interval, value)}
      stay() using Some(interval, cmp)
  }
  
  initialize

}