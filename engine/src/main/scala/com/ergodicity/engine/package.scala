package com.ergodicity

import com.typesafe.config.ConfigFactory

package object engine {
  val EngineSystemConfig = ConfigFactory.parseString("""
    akka.loglevel = DEBUG

    akka.event-handlers = ["akka.testkit.TestEventListener"]

    akka.actor.debug {
      receive = off
      lifecycle = on
      fsm = on
    }

    deque-dispatcher {
      mailbox-type = "akka.dispatch.UnboundedDequeBasedMailbox"
    }

    engine {
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

           tradingDispatcher {
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