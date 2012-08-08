package com.ergodicity.engine

import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.{Terminated, ActorSystem}
import akka.testkit.{TestFSMRef, TestProbe, ImplicitSender, TestKit}
import service.Service.{Stop, Start}
import service.{ServiceStopped, ServiceStarted, Service}

class ServiceManagerSpec extends TestKit(ActorSystem("ServiceManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  case object Service1 extends Service

  case object Service2 extends Service

  "Service Manager" must {
    "start all registered services" in {
      given("Service Manager with two registered services: Service1 and Service2")
      val service1 = TestProbe()
      val service2 = TestProbe()

      val serviceManager = TestFSMRef(new ServiceManager, "ServiceManager")
      serviceManager ! RegisterService(Service1, service1.ref)
      serviceManager ! RegisterService(Service2, service2.ref)

      when("service manager starts all services")
      serviceManager ! StartAllServices

      then("each service get Start message")
      service1.expectMsg(Start)
      service2.expectMsg(Start)

      when("Service1 started")
      serviceManager ! ServiceStarted(Service1)

      then("service2 should be notofied")
      service2.expectMsg(ServiceStarted(Service1))

      when("Service2 started")
      serviceManager ! ServiceStarted(Service2)

      then("service1 should be notofied")
      service1.expectMsg(ServiceStarted(Service2))

      and("Service Manager should go to Active state")

      assert(serviceManager.stateName == ServiceManagerState.Active,
        "Service Manager state = " + serviceManager.stateName + "; Data = " + serviceManager.stateData)
    }

    "stop all registered services" in {
      given("Service Manager with two registered services: Service1 and Service2")
      val service1 = TestProbe()
      val service2 = TestProbe()

      val serviceManager = TestFSMRef(new ServiceManager, "ServiceManager")
      serviceManager ! RegisterService(Service1, service1.ref)
      serviceManager ! RegisterService(Service2, service2.ref)

      watch(serviceManager)
      serviceManager.setState(ServiceManagerState.Active)

      when("Servie Manager stops all services")
      serviceManager ! StopAllServices

      then("should go to Stopping state")
      assert(serviceManager.stateName == ServiceManagerState.Stopping)

      and("each service get Stop message")
      service1.expectMsg(Stop)
      service2.expectMsg(Stop)

      when("Service1 stopped")
      serviceManager ! ServiceStopped(Service1)

      then("service2 should be notofied")
      service2.expectMsg(ServiceStopped(Service1))

      when("Service2 stopped")
      serviceManager ! ServiceStopped(Service2)

      then("service1 should be notofied")
      service1.expectMsg(ServiceStopped(Service2))

      and("Service Manager should be termindated")
      expectMsg(Terminated(serviceManager))
    }

  }
}