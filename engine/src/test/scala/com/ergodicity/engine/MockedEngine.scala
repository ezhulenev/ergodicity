package com.ergodicity.engine

import akka.event.LoggingAdapter
import akka.actor.ActorRef

class MockedEngine(_log: LoggingAdapter, _serviceTracker: ActorRef) extends Engine {
  def log = _log

  def ServiceTracker = _serviceTracker
}
