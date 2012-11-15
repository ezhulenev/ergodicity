package com.ergodicity.engine.underlying

import ru.micexrts.cgate.{Listener, ISubscriber, Connection}
import com.ergodicity.cgate.config.ListenerConfig

trait ListenerFactory {
  def apply(connection: Connection, config: ListenerConfig, subscriber: ISubscriber): Listener
}

trait UnderlyingListener {
  def listenerFactory: ListenerFactory
}