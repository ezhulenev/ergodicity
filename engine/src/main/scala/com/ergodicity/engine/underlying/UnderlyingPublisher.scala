package com.ergodicity.engine.underlying

import ru.micexrts.cgate.{Publisher => CGPublisher}

trait UnderlyingPublisher {
  def publisherName: String
  def brokerCode: String
  def underlyingPublisher: CGPublisher
}