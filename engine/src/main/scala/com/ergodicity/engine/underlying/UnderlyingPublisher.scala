package com.ergodicity.engine.underlying

import ru.micexrts.cgate.{Publisher => CGPublisher}

trait UnderlyingPublisher {
  def underlyingPublisher: CGPublisher
}