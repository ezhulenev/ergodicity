package integration.ergodicity.core

import com.typesafe.config.ConfigFactory

object AkkaIntegrationConfigurations {
  val ConfigWithDetailedLogging = ConfigFactory.parseString("""
    akka.actor.debug {
      receive = on
      lifecycle = on
    }

    akka {
      event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
      loglevel = "DEBUG"
    }

                                                            """)
}
