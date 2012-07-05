package com.ergodicity.engine.component

import plaza2.{Connection => P2Connection}

trait ConnectionComponent {
  def underlyingConnection: P2Connection
}