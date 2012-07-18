package com.ergodicity.cgate

import org.mockito.Mockito._
import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import ru.micexrts.cgate.{Listener => CGListener}

class ListenerSpec extends TestKit(ActorSystem("ListenerSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  val Host = "host"
  val Port = 4001
  val AppName = "ConnectionSpec"

  override def afterAll() {
    system.shutdown()
  }

  "Listener" must {
    "be initialized in Closed state" in {
      val cg = (s: Subscriber) => mock(classOf[CGListener])

      val listener = TestFSMRef(new Listener(cg), "Listener")
      log.info("State: " + listener.stateName)
      assert(listener.stateName == Closed)
    }
  }

}
