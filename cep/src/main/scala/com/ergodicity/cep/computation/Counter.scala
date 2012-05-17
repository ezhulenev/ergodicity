package com.ergodicity.cep.computation

import com.ergodicity.cep.MarketEvent
import org.scala_tools.time.Implicits._
import scalaz.NonEmptyList
import org.joda.time.{Duration, Interval}

object Counter {
  def continuous[E]() = new ContinuousCounter[E]()

  def framed[E <: MarketEvent](frame: Interval) = new FramedCounter[E](frame)

  def sliding[E <: MarketEvent](duration: Duration) = new SlidingCounter[E](duration)()
}

class ContinuousCounter[P](cnt: Int = 0) extends ContinuousComputation[P, Int] {
  def apply() = cnt

  def apply(el: P) = new ContinuousCounter(cnt + 1)
}

class FramedCounter[E <: MarketEvent](val frame: Interval, cnt: Int = 0) extends FramedComputation[E, Int] {
  import com.ergodicity.cep.Implicits._
  def sequent() = new FramedCounter[E](frame.sequent)

  def apply() = cnt

  def apply(event: E) = {
    if (frame contains event.time)
      FramedReaction.Stay(new FramedCounter[E](frame, cnt + 1))
    else if (event.time < frame.start)
      FramedReaction.Stay(this)
    else {
      val span = Stream.iterate(new FramedCounter[E](frame.sequent))(previous => new FramedCounter[E](previous.frame.sequent)).span(!_.frame.contains(event.time))
      val slideOut = NonEmptyList(this) :::> span._1.toList
      span._2.head(event) match {
        case FramedReaction.Stay(c: FramedCounter[E]) => FramedReaction.Jump(slideOut, c)
        case _ => throw new IllegalStateException()
      }
    }
  }  
}


class SlidingCounter[E <: MarketEvent](val duration: Duration)(events: Seq[E] = Seq()) extends SlidingComputation[E, Int] {

  def apply() = events.size

  def apply(event: E) = {

    val outdatedEvents = events.takeWhile(_.time <= event.time - duration)
    
    if (outdatedEvents.size == 0) {
      SlidingReaction.Stay(new SlidingCounter[E](duration)(events :+ event))
    } else {
      val slideOut = NonEmptyList(outdatedEvents.head) :::> outdatedEvents.tail.toList
      val cnt = new SlidingCounter[E](duration)(events.drop(outdatedEvents.size) :+ event)
      SlidingReaction.Slide(slideOut, cnt)
    }
  }
}
