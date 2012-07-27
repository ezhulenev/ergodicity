package com.ergodicity.engine

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.ActorSystem
import akka.util.duration._
import akka.testkit.{TestFSMRef, TestProbe, ImplicitSender, TestKit}
import service.{ServicePassivated, ServiceFailed, ServiceActivated, Service}

class EngineSpec extends TestKit(ActorSystem("EngineWithConnectionSpec", com.ergodicity.engine.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Engine" must {
    "forward Service* messages" in {
      val probe = TestProbe()
      val engine = TestFSMRef(new Engine with TestingService {
        lazy val forwardTo = probe
      }, "Engine")

      // Forward for other service
      engine ! ServiceActivated(SomeService)
      probe.expectMsg(ServiceActivated(SomeService))
      engine ! ServicePassivated(SomeService)
      probe.expectMsg(ServicePassivated(SomeService))
      engine ! ServiceFailed(SomeService)
      probe.expectMsg(ServiceFailed(SomeService))

      // Skip for self service
      engine ! ServiceActivated(TestingService)
      engine ! ServicePassivated(TestingService)
      engine ! ServiceFailed(TestingService)

      expectNoMsg(100.millis)
    }
  }

  case object SomeService extends Service

  case object TestingService extends Service

  trait TestingService {
    self: Engine =>
    def forwardTo: TestProbe

    registerService(TestingService, forwardTo.ref)
  }

}