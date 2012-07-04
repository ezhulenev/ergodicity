package com.ergodicity.engine.component

import com.ergodicity.plaza2.Connection
import plaza2.{Connection => P2Connection}

trait ConnectionComponent {
  def connectionCreator: Connection
}

trait Plaza2ConnectionComponent extends ConnectionComponent {
  lazy val connectionCreator = Connection(P2Connection())
}