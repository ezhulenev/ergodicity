package com.ergodicity

import com.typesafe.config.ConfigFactory

package object engine {
  val EngineSystemConfig = ConfigFactory.parseString("""
    akka.loglevel = DEBUG

    akka.event-handlers = ["akka.testkit.TestEventListener"]

    akka.actor.debug {
      receive = off
      lifecycle = off
      fsm = off
    }

    deque-dispatcher {
      mailbox-type = "akka.dispatch.UnboundedDequeBasedMailbox"
    }

                                                     """)
}