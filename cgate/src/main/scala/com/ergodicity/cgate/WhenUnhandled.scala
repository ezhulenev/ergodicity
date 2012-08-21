package com.ergodicity.cgate

import akka.actor.{ActorLogging, Actor}
import akka.event.LoggingAdapter

trait WhenUnhandled { this: Actor with ActorLogging =>

  def whenUnhandled: Receive = {
    case e => log.warning("Unhandled event: " + e)
  }
}