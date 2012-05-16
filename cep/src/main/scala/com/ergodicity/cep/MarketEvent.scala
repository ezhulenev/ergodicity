package com.ergodicity.cep

import org.joda.time.DateTime

trait MarketEvent {
  def time: DateTime
}