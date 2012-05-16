package com.ergodicity.cep.computation

import com.ergodicity.cep.MarketEvent
import org.joda.time.Interval
import org.scala_tools.time.Implicits._
import scalaz.NonEmptyList

object Counter {
  def continuous[P]() = new ContinuousCounter[P]()

  def framed[P <: MarketEvent](frame: Interval) = new FramedCounter[P](frame)

//  def sliding[P <: MarketEvent](duration: Duration) = new SlidingCounter[P](duration)()
}

class ContinuousCounter[P](cnt: Option[Int] = None) extends ContinuousComputation[P, Int] {
  def apply() = cnt

  def apply(el: P) = new ContinuousCounter(Some(cnt.map(_ + 1) getOrElse 1))
}

class FramedCounter[E <: MarketEvent](val frame: Interval, cnt: Option[Int] = None) extends FramedComputation[E, Int] {
  import com.ergodicity.cep.Implicits._
  def sequent() = new FramedCounter[E](frame.sequent)

  def apply() = cnt

  def apply(el: E) = {
    if (frame contains el.time)
      FramedReaction.Stay(new FramedCounter[E](frame, Some(cnt.map(_ + 1) getOrElse 1)))
    else if (el.time < frame.start)
      FramedReaction.Stay(this)
    else {
      val span = Stream.iterate(new FramedCounter[E](frame.sequent))(previous => new FramedCounter[E](previous.frame.sequent)).span(!_.frame.contains(el.time))
      val slideOut = NonEmptyList(this) :::> span._1.toList
      span._2.head(el) match {
        case FramedReaction.Stay(c: FramedCounter[E]) => FramedReaction.Jump(slideOut, c)
        case _ => throw new IllegalStateException()
      }
    }
  }  
}

/*
class SlidingCounter[P <: MarketEvent](val duration: Duration)(events: Seq[DateTime] = Seq()) extends SlidingComputation[P, Int] {

  def apply() = if (events.size == 0) None else Some(events.size)

  def apply(el: P) = {
    events.span()
    val dropped = events.dropWhile(_ < el.time - duration)
    new SlidingCounter(duration)(el.time +: dropped)
  }
}*/
