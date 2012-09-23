package integration.ergodicity.core

import com.typesafe.config.ConfigFactory

object AkkaIntegrationConfigurations {
  val ConfigWithDetailedLogging = ConfigFactory.parseString("""
    akka.actor.debug {
      receive = off
      lifecycle = off
      fsm = off
    }

    akka {
      # event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
      event-handlers = ["akka.event.Logging$DefaultLogger"]
      loglevel = "DEBUG"
    }

    integration {
        dispatchers {
            replicationDispatcher {
              type = Dispatcher

              executor = "thread-pool-executor"

              # Single Threaded Executor
              thread-pool-executor {
                core-pool-size-min = 1
                core-pool-size-max = 1
                max-pool-size-min = 1
                max-pool-size-max = 1
              }

              throughput = 1
            }
        }
    }


                                                            """)
}
