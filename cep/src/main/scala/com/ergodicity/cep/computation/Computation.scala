package com.ergodicity.cep.computation

import org.joda.time.{Interval, Duration}

protected sealed trait Computation[A, B] {
  type Self <: Computation[A, B]
  
  def apply(): Option[B]

  def apply(el: A): Self
}

trait ContinuousComputation[A, B] extends Computation[A, B] {
  type Self = ContinuousComputation[A, B]
}

trait SlidingComputation[A, B] extends Computation[A, B] {
  type Self = SlidingComputation[A, B]

  def duration: Duration
}

trait FramedComputation[A, B] extends Computation[A, B] {
  type Self = FramedComputation[A, B]

  def frame: Interval

  def sequent(): FramedComputation[A, B]
}

