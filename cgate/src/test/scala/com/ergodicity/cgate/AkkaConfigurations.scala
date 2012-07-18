package com.ergodicity.cgate

import com.typesafe.config.ConfigFactory

object AkkaConfigurations {
  val ConfigWithDetailedLogging = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.actor.debug {
      receive = on
      lifecycle = on
    }
    """)
}
