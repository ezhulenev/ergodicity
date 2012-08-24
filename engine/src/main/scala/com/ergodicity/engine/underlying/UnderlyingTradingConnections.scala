package com.ergodicity.engine.underlying

import ru.micexrts.cgate.{Connection => CGConnection}

trait UnderlyingTradingConnections {
  def underlyingPublisherConnection: CGConnection

  def underlyingRepliesConnection: CGConnection
}