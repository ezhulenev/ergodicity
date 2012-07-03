package com.ergodicity.quant

import org.joda.time.DateTime

trait MarketEvent {
  def time: DateTime
}