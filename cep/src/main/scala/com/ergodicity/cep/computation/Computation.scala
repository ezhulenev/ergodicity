package com.ergodicity.cep.computation

import org.joda.time.{Interval, Duration}
import scalaz.NonEmptyList

protected sealed trait Computation[E, C] {
  def apply(): Option[C]
}

trait ContinuousComputation[E, C] extends Computation[E, C] {
  def apply(event: E): ContinuousComputation[E, C]
}



sealed trait SlidingReaction[E, C]
object SlidingReaction {
  case class Stay[E, C](computation: SlidingComputation[E, C]) extends SlidingReaction[E, C]
  case class Slide[E, C](slideOut: NonEmptyList[E], computation: SlidingComputation[E, C]) extends SlidingReaction[E, C]
}

trait SlidingComputation[E, C] extends Computation[E, C] {
  def duration: Duration
  def apply(event: E): SlidingReaction[E, C]
}



sealed trait FramedReaction[E, C]
object FramedReaction {
  case class Stay[E, C](computation: FramedComputation[E, C]) extends FramedReaction[E, C]
  case class Jump[E, C](slideOut: NonEmptyList[FramedComputation[E, C]], computation: FramedComputation[E, C]) extends FramedReaction[E, C]
}


trait FramedComputation[E, C] extends Computation[E, C] {
  def frame: Interval
  def apply(event: E): FramedReaction[E, C]
}

