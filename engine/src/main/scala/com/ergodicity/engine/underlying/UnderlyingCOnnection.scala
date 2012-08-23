package com.ergodicity.engine.underlying

import ru.micexrts.cgate.{Connection => CGConnection}

trait UnderlyingConnection {
  def underlyingConnection: CGConnection
}
