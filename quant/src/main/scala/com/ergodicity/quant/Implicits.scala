package com.ergodicity.quant

import org.joda.time.{Duration, Interval}


object Implicits {
  implicit def pimpInterval(interval: Interval) = new IntervalW(interval)
}

class IntervalW(interval: Interval) {
  import org.scala_tools.time.Implicits._

  def sequent = new Interval(interval.start + interval.duration, interval.end + interval.duration)

  def append(duration: Duration) = new Interval(interval.start + duration, interval.end + duration)
}