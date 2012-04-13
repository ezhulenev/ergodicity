package com.ergodicity.engine

import com.typesafe.config.ConfigFactory

package object plaza2 {
  val Config = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.actor.debug {
      receive = on
      lifecycle = on
    }
    """)
}
