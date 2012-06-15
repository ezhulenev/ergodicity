package com.ergodicity.core.common

import akka.actor.Actor
import akka.event.LoggingAdapter

trait WhenUnhandled {
  this: Actor {def log: LoggingAdapter} =>

  def whenUnhandled: Receive = {
    case e => log.warning("Unhandled event: " + e)
  }
}