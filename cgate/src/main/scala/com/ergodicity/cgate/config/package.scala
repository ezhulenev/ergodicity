package com.ergodicity.cgate

import ru.micexrts.cgate.{Connection => CGConnection}

package object config {
  implicit def buildConnection(conn: ConnectionConfig) = new CGConnection(conn.config)
}