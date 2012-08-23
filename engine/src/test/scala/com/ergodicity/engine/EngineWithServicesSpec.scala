package com.ergodicity.engine

import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.util.Timeout
import akka.util.duration._
import service.ServiceId
import com.ergodicity.engine.ServiceManager.ServiceRef

class EngineWithServicesSpec extends TestKit(ActorSystem("EngineWithServicesSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val timeout = Timeout(1.second)

  case object NoSuchService extends ServiceId

  case object MyService extends ServiceId

  class EngineUnderTest extends Engine with ManagedServices

  "Engine with Services" must {

    "throw exception if not service registered" in {
      val engine = TestActorRef(new EngineUnderTest())
      val underlying = engine.underlyingActor
      intercept[ServiceNotFoundException] {
        underlying.service(NoSuchService)
      }
    }

    "return registered service ref" in {
      val engine = TestActorRef(new EngineUnderTest())
      val underlying = engine.underlyingActor
      underlying.ServiceManager ! RegisterService(MyService, self)
      val ref = underlying.service(MyService)
      assert(ref == ServiceRef(MyService, self))
    }
  }
}
