package com.ergodicity.engine

import akka.event.LoggingAdapter
import akka.actor.ActorRef

class MockedEngine(_log: LoggingAdapter, _serviceManager: ActorRef) extends Engine {
  def log = _log

  def ServiceManager = _serviceManager
}
