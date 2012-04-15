package integration.ergodicity.engine.core

import com.typesafe.config.ConfigFactory

object AkkaIntegrationConfigurations {
  val ConfigWithDetailedLogging = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.actor.debug {
      receive = on
      lifecycle = on
    }
    """)
}
