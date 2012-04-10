package com.ergodicity.engine.plaza2

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory
import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKit}
import com.ergodicity.engine.plaza2.Connection.Connect
import plaza2.{Connection => P2Connection}

class ConnectionIntegrationSpec extends TestKit(ActorSystem()) with WordSpec {
  val log = LoggerFactory.getLogger(classOf[ConnectionIntegrationSpec])

  val Host = "localhost"
  val Port = 4001
  val AppName = "ConnectionIntegrationSpec"

  "Connection" must {
    "work" in {
      val underlying = P2Connection()
      val connection = TestFSMRef(Connection(underlying), "Connection")
      connection ! Connect(Host, Port, AppName)

      Thread.sleep(1000)
    }
  }

}