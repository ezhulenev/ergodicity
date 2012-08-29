package com.ergodicity.engine

import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor._
import akka.testkit._
import akka.util.duration._
import service.Service.{Stop, Start}
import service.ServiceId
import com.ergodicity.engine.Services.{StopAllServices, StartAllServices}
import akka.actor.Terminated
import com.ergodicity.engine.ServicesState.PendingServices

class ServicesSpec extends TestKit(ActorSystem("ServicesSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Services Manager" must {

    "get registered service" in {
      val serviceManager = TestActorRef(new TwoServices(self, self), "Services")
      val underlying = serviceManager.underlyingActor

      val expected = system.actorFor("/user/Services/Service1")
      log.info("Expected ref = " + expected)
      assert(underlying.service(Service1.Service1) == expected,
        "Actual service ref = " + underlying.service(Service1.Service1))
    }

    "start all registered without additional dependencies services" in {
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

      and("Service2 started")
      serviceManager ! ServiceStarted(Service2.Service2)

      then("Service Manager should go to Active state")

      assert(serviceManager.stateName == ServicesState.Active,
        "Service Manager state = " + serviceManager.stateName + "; Data = " + serviceManager.stateData)
    }

    "stop all registered without additional dependencies services" in {
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

      and("Service2 stopped")
      serviceManager ! ServiceStopped(Service2.Service2)

      then("Service Manager should be termindated")
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

      assert(underlying.services(DependentService.DependentService).startBarrier.toSeq match {
        case startUp :: Service1.Service1 :: Service2.Service2 :: Nil => true
        case _ => false
      })

      assert(underlying.services(Service1.Service1).stopBarrier.toSeq match {
        case shutDown :: DependentService.DependentService :: Nil => true
        case _ => false
      })

      assert(underlying.services(Service2.Service2).stopBarrier.toSeq match {
        case shutDown :: DependentService.DependentService :: Nil => true
        case _ => false
      })
    }

    "start all registered services according to their dependencies" in {
      given("Service Manager with three services")
      val s1 = TestProbe()
      val s2 = TestProbe()
      val dep = TestProbe()

      val serviceManager = TestFSMRef(new ThreeServices(s1.ref, s2.ref, dep.ref), "Services")

      when("service manager starts all services")
      serviceManager ! StartAllServices

      then("Service1 & Service2 get Start message")
      s1.expectMsg(Start)
      s2.expectMsg(Start)

      and("Dependent service get no messages")
      dep.expectNoMsg(500.millis)

      when("Service1 started")
      serviceManager ! ServiceStarted(Service1.Service1)

      and("Service2 started")
      serviceManager ! ServiceStarted(Service2.Service2)

      then("Dependent service get Start message")
      dep.expectMsg(Start)

      when("last service started")
      serviceManager ! ServiceStarted(DependentService.DependentService)

      then("Service Manager goes to Active state")
      assert(serviceManager.stateName == ServicesState.Active,
        "Service Manager state = " + serviceManager.stateName + "; Data = " + serviceManager.stateData)
    }

    "stop all registered services according to their dependencies" in {
      given("Service Manager with three services")
      val s1 = TestProbe()
      val s2 = TestProbe()
      val dep = TestProbe()

      val serviceManager = TestFSMRef(new ThreeServices(s1.ref, s2.ref, dep.ref), "Services")
      serviceManager.setState(ServicesState.Active)
      watch(serviceManager)

      when("service manager starts all services")
      serviceManager ! StopAllServices

      then("Dependent service get Stop message")
      dep.expectMsg(Stop)

      and("Service1 & Service2 get no messages")
      s1.expectNoMsg(150.millis)
      s2.expectNoMsg(150.millis)

      when("Dependent service stopped started")
      serviceManager ! ServiceStopped(DependentService.DependentService)

      then("Service2 & Service2 get Stop message")
      s1.expectMsg(Stop)
      s2.expectMsg(Stop)


      when("all services Stopped")
      serviceManager ! ServiceStopped(Service1.Service1)
      serviceManager ! ServiceStopped(Service2.Service2)

      then("Service Manager should stop itself")
      expectMsg(Terminated(serviceManager))
    }

    "stop itself on Timed out in Stopping state" in {
      val s1 = TestProbe()
      val s2 = TestProbe()
      val dep = TestProbe()

      val serviceManager = TestFSMRef(new ThreeServices(s1.ref, s2.ref, dep.ref), "Services")
      serviceManager.setState(ServicesState.Stopping, PendingServices(Nil))
      watch(serviceManager)

      serviceManager ! FSM.StateTimeout

      expectMsg(Terminated(serviceManager))
    }


    "fail to register Service if dependency is not provided" in {
      val serviceManager = TestActorRef(new Services, "Services")
      val underlying = serviceManager.underlyingActor

      intercept[IllegalStateException] {
        implicit val id = DependentService.DependentService
        underlying.register(Props(new EmptyActor), dependOn = Service1.Service1 :: Nil)
      }
    }
  }

  class EmptyActor extends Actor {
    protected def receive = null
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

    register(Props(PassThrough(service1)))
  }

  trait Service2 {
    self: Services =>

    import Service2._

    def service2: ActorRef

    register(Props(PassThrough(service2)))
  }

  trait DependentService {
    self: Services =>

    import DependentService._

    def dependent: ActorRef

    register(Props(PassThrough(dependent)), dependOn = Seq(Service1.Service1, Service2.Service2))
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

  case class PassThrough(ref: ActorRef) extends Actor {
    protected def receive = {
      case m => ref ! m
    }
  }
}