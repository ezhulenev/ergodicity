package com.ergodicity.engine.underlying

import ru.micexrts.cgate.{Listener, ISubscriber, Connection}

trait ListenerFactory {
  def apply(connection: Connection, config: String, subscriber: ISubscriber): Listener
}

trait UnderlyingListener {
  def listenerFactory: ListenerFactory
}