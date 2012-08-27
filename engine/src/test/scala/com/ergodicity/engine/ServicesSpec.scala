package com.ergodicity.engine

import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit._
import service.Service.{Stop, Start}
import service.{ServiceStopped, ServiceStarted, ServiceId}
import com.ergodicity.engine.Services.{StopAllServices, StartAllServices}
import akka.actor.Terminated

class ServicesSpec extends TestKit(ActorSystem("ServicesSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  object Service1 {

    implicit case object Service1 extends ServiceId

  }

  object Service2 {

    implicit case object Service2 extends ServiceId

  }

  object DependentService {

    implicit case object DependentService extends ServiceId

  }

  trait Service1 {
    self: Services =>

    import Service1._

    def service1: ActorRef

    register(service1)
  }

  trait Service2 {
    self: Services =>

    import Service2._

    def service2: ActorRef

    register(service2)
  }

  trait DependentService {
    self: Services =>

    import DependentService._

    def dependent: ActorRef

    register(dependent, dependOn = Seq(Service1.Service1, Service2.Service2))
  }

  class TwoServices(s1: ActorRef, s2: ActorRef) extends Services with Service1 with Service2 {
    def service1 = s1

    def service2 = s2
  }

  class ThreeServices(s1: ActorRef, s2: ActorRef, dep: ActorRef) extends Services with Service1 with Service2 with DependentService {
    def service1 = s1

    def service2 = s2

    def dependent = dep
  }

  class BadDependent(s1: ActorRef, s2: ActorRef, dep: ActorRef) extends Services with DependentService with Service1 with Service2 {
    def service1 = s1

    def service2 = s2

    def dependent = dep
  }


  "Service Manager" must {

    "get registered service" in {
      val serviceManager = TestActorRef(new TwoServices(self, self), "Services")
      val underlying = serviceManager.underlyingActor

      assert(underlying.service(Service1.Service1) == self, "Actual service ref = " + underlying.service(Service1.Service1))
    }

    "start all registered services" in {
      given("Service Manager with two registered services: Service1 and Service2")
      val srv1 = TestProbe()
      val srv2 = TestProbe()

      val serviceManager = TestFSMRef(new TwoServices(srv1.ref, srv2.ref), "Services")

      when("service manager starts all services")
      serviceManager ! StartAllServices

      then("each service get Start message")
      srv1.expectMsg(Start)
      srv2.expectMsg(Start)

      when("Service1 started")
      serviceManager ! ServiceStarted(Service1.Service1)

      then("service2 should be notofied")
      srv2.expectMsg(ServiceStarted(Service1.Service1))

      when("Service2 started")
      serviceManager ! ServiceStarted(Service2.Service2)

      then("service1 should be notofied")
      srv1.expectMsg(ServiceStarted(Service2.Service2))

      and("Service Manager should go to Active state")

      assert(serviceManager.stateName == ServicesState.Active,
        "Service Manager state = " + serviceManager.stateName + "; Data = " + serviceManager.stateData)
    }

    "stop all registered services" in {
      given("Service Manager with two registered services: Service1 and Service2")
      val srv1 = TestProbe()
      val srv2 = TestProbe()

      val serviceManager = TestFSMRef(new TwoServices(srv1.ref, srv2.ref), "Services")
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
      serviceManager ! ServiceStopped(Service1.Service1)

      then("service2 should be notofied")
      srv2.expectMsg(ServiceStopped(Service1.Service1))

      when("Service2 stopped")
      serviceManager ! ServiceStopped(Service2.Service2)

      then("service1 should be notofied")
      srv1.expectMsg(ServiceStopped(Service2.Service2))

      and("Service Manager should be termindated")
      expectMsg(Terminated(serviceManager))
    }

    "register service with dependency" in {
      val s1 = TestProbe()
      val s2 = TestProbe()
      val dep = TestProbe()

      val serviceManager = TestActorRef(new ThreeServices(s1.ref, s2.ref, dep.ref), "Services")
      val underlying = serviceManager.underlyingActor

      log.info("Services = " + underlying.services)

      assert(underlying.services.size == 3)
    }

    "fail to start Services if not all dependency services provided" in {
      val serviceManager = TestActorRef(new Services with Service1 with DependentService {
        def service1 = system.deadLetters

        def dependent = system.deadLetters
      }, "Services")
      val underlying = serviceManager.underlyingActor

      intercept[IllegalStateException] {
        underlying.preStart()
      }
    }
  }
}