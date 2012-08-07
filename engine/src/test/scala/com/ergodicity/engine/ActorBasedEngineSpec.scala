package com.ergodicity.engine

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.ActorSystem
import akka.util.duration._
import akka.testkit.{TestFSMRef, TestProbe, ImplicitSender, TestKit}
import service.{ServicePassivated, ServiceActivated, Service}
import com.ergodicity.engine.EngineState.Starting
import com.ergodicity.engine.EngineData.Activating

class AkkaEngineSpec extends TestKit(ActorSystem("AkkaEngineWithManagedConnectionSpec", com.ergodicity.engine.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Engine" must {
    "forward Service* messages" in {
      val probe = TestProbe()
      val engine = TestFSMRef(new AkkaEngine with ForwardingService with DummyService {
        lazy val forwardTo = probe
      }, "Engine")

      engine.setState(Starting, Activating(DummyService :: ForwardingService :: Nil))

      // Forward for other service
      engine ! ServiceActivated(DummyService)
      probe.expectMsg(ServiceActivated(DummyService))
      engine ! ServicePassivated(DummyService)
      probe.expectMsg(ServicePassivated(DummyService))

      // Skip for self service
      engine ! ServiceActivated(ForwardingService)
      engine ! ServicePassivated(ForwardingService)

      expectNoMsg(100.millis)

      assert(engine.stateName == EngineState.Active, "Engine state = " + engine.stateName + "; Data = " + engine.stateData)
    }
  }

  case object DummyService extends Service

  case object ForwardingService extends Service

  trait DummyService {
    self: Engine =>
    registerService(DummyService, system.deadLetters)
  }

  trait ForwardingService {
    self: Engine =>
    def forwardTo: TestProbe

    registerService(ForwardingService, forwardTo.ref)
  }

}