package com.ergodicity.engine

import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.{ActorRef, Terminated, ActorSystem}
import akka.testkit._
import service.Service.{Stop, Start}
import service.ServiceStarted
import service.ServiceStopped
import service.{ServiceStopped, ServiceStarted, ServiceId}
import com.ergodicity.engine.Services.{StopAllServices, StartAllServices}
import akka.actor.Terminated

class ServicesSpec extends TestKit(ActorSystem("ServicesSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Service Manager" must {
    "get registered service" in {
      val serviceManager = TestActorRef(new Services with Service1 {
        def service1 = self
      }, "Services")
      val underlying = serviceManager.underlyingActor

      assert(underlying.service(ServiceId1) == self)
    }

    "start all registered services" in {
      given("Service Manager with two registered services: Service1 and Service2")
      val srv1 = TestProbe()
      val srv2 = TestProbe()

      val serviceManager = TestFSMRef(new Services with Service1 with Service2 {
        def service1 = srv1.ref

        def service2 = srv2.ref
      }, "Services")

      when("service manager starts all services")
      serviceManager ! StartAllServices

      then("each service get Start message")
      srv1.expectMsg(Start)
      srv2.expectMsg(Start)

      when("Service1 started")
      serviceManager ! ServiceStarted(ServiceId1)

      then("service2 should be notofied")
      srv2.expectMsg(ServiceStarted(ServiceId1))

      when("Service2 started")
      serviceManager ! ServiceStarted(ServiceId2)

      then("service1 should be notofied")
      srv1.expectMsg(ServiceStarted(ServiceId2))

      and("Service Manager should go to Active state")

      assert(serviceManager.stateName == ServicesState.Active,
        "Service Manager state = " + serviceManager.stateName + "; Data = " + serviceManager.stateData)
    }

    "stop all registered services" in {
      given("Service Manager with two registered services: Service1 and Service2")
      val srv1 = TestProbe()
      val srv2 = TestProbe()

      val serviceManager = TestFSMRef(new Services with Service1 with Service2 {
        def service1 = srv1.ref

        def service2 = srv2.ref
      }, "Services")

      watch(serviceManager)
      serviceManager.setState(ServicesState.Active)

      when("Servie Manager stops all services")
      serviceManager ! StopAllServices

      then("should go to Stopping state")
      assert(serviceManager.stateName == ServicesState.Stopping)

      and("each service get Stop message")
      srv1.expectMsg(Stop)
      srv2.expectMsg(Stop)

      when("Service1 stopped")
      serviceManager ! ServiceStopped(ServiceId1)

      then("service2 should be notofied")
      srv2.expectMsg(ServiceStopped(ServiceId1))

      when("Service2 stopped")
      serviceManager ! ServiceStopped(ServiceId2)

      then("service1 should be notofied")
      srv1.expectMsg(ServiceStopped(ServiceId2))

      and("Service Manager should be termindated")
      expectMsg(Terminated(serviceManager))
    }
  }

  case object ServiceId1 extends ServiceId

  case object ServiceId2 extends ServiceId

  trait Service1 {
    self: Services =>

    def service1: ActorRef

    register(ServiceId1, service1)
  }

  trait Service2 {
    self: Services =>

    def service2: ActorRef

    register(ServiceId2, service2)
  }

}