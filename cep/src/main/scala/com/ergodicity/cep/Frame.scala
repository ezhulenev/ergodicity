package com.ergodicity.cep

import org.scala_tools.time.Implicits._
import org.joda.time.{DateTime, Interval}


sealed trait FrameReaction[P <: Payload] {
  def frame: Frame[P]
}

case class Accumulate[P <: Payload](frame: Frame[P]) extends FrameReaction[P]

case class Overflow[P <: Payload](frame: Frame[P]) extends FrameReaction[P]

case class Frame[P <: Payload](interval: Interval, payload: List[P] = List()) {
  def <<<(data: P) = {
    if (invalidTimeStamp(data.time)) {
      throw new IllegalArgumentException("Invalid timestamp")
    } else if (overflowed(data.time)) {
      Overflow(Frame[P](nextInterval))
    } else {
      Accumulate(copy(payload = data :: Nil))
    }
  }

  private val nextInterval = new Interval(interval.start + interval.duration, interval.end + interval.duration)

  def lastTimestamp = payload.lastOption.map(_.time)

  private def invalidTimeStamp(time: DateTime) = time < interval.start || payload.length > 0 && time < payload.last.time

  private def overflowed(time: DateTime) = time > interval.end
}