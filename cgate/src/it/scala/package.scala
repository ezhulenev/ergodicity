import com.typesafe.config.ConfigFactory

package object integration {
  val ConfigWithDetailedLogging = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
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