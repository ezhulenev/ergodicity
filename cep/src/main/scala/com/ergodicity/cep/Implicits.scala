package com.ergodicity.cep

import org.joda.time.Interval

object Implicits {
  implicit def pimpInterval(interval: Interval) = new IntervalW(interval)
}

class IntervalW(interval: Interval) {
  import org.scala_tools.time.Implicits._

  def sequent = new Interval(interval.start + interval.duration, interval.end + interval.duration)
}