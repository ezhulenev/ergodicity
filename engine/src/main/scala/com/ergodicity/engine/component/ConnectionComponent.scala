package com.ergodicity.engine.component

import ru.micexrts.cgate.{Connection => CGConnection}
import com.ergodicity.cgate.config.ConnectionConfig

trait ConnectionComponent {
  def underlyingConnection: CGConnection
}

trait CGateConnection extends ConnectionComponent {
  def connectionConfig: ConnectionConfig

  lazy val underlyingConnection = new CGConnection(connectionConfig())
}