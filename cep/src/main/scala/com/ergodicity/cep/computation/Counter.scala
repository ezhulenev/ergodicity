package com.ergodicity.cep.computation

import com.ergodicity.cep.Payload
import org.joda.time.{DateTime, Duration, Interval}
import org.scala_tools.time.Implicits._

object Counter {
  def continuous[P]() = new ContinuousCounter[P]()

  def framed[P <: Payload](frame: Interval) = new FramedCounter[P](frame)

  def sliding[P <: Payload](duration: Duration) = new SlidingCounter[P](duration)()
}

class ContinuousCounter[P](cnt: Option[Int] = None) extends ContinuousComputation[P, Int] {
  def apply() = cnt

  def apply(el: P) = new ContinuousCounter(Some(cnt.map(_ + 1) getOrElse 1))
}

class FramedCounter[P <: Payload](val frame: Interval, cnt: Option[Int] = None) extends FramedComputation[P, Int] {
  import com.ergodicity.cep.Implicits._
  def sequent() = new FramedCounter[P](frame.sequent)

  def apply() = cnt

  def apply(el: P) = if (frame contains el.time) new FramedCounter[P](frame, Some(cnt.map(_ + 1) getOrElse 1)) else this
}

class SlidingCounter[P <: Payload](val duration: Duration)(events: Seq[DateTime] = Seq()) extends SlidingComputation[P, Int] {

  def apply() = if (events.size == 0) None else Some(events.size)

  def apply(el: P) = {
    val dropped = events.dropWhile(_ < el.time - duration)
    new SlidingCounter(duration)(el.time +: dropped)
  }
}