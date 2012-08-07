package com.ergodicity.engine

import ru.micexrts.cgate.{ISubscriber, Connection => CGConnection,Listener => CGListener}
import com.ergodicity.cgate.config.Replication

object EngineComponents

trait CreateListener {
  def listener(connection: CGConnection, config: String, subscriber: ISubscriber): CGListener
}

trait FutInfoReplication {
  def futInfoReplication: Replication
}

trait OptInfoReplication {
  def optInfoReplication: Replication
}