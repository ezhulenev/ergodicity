package com.ergodicity.engine.service

import akka.actor.{PoisonPill, ActorContext, Terminated, ActorSystem}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import ru.micexrts.cgate.{Connection => CGConnection}
import org.mockito.Mockito._
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.engine.{Services, Strategies, ServiceFailedException, Engine}
import com.ergodicity.engine.underlying.UnderlyingConnection
import com.ergodicity.engine.Services.ServiceFailedException

class ConnectionManagerSpec extends TestKit(ActorSystem("ConnectionManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  private def mockEngine = TestActorRef(new Engine with UnderlyingConnection {
    val underlyingConnection = mock(classOf[CGConnection])
  })

  private def mockServices = TestActorRef(new Services)

  "ConnectionManager" must {
    "throw exception on error state" in {
      val engine = mockEngine.underlyingActor
      val services = mockServices.underlyingActor

      val manager = TestActorRef(new ConnectionManager(services, engine))
      intercept[ServiceFailedException] {
        manager.receive(CurrentState(manager.underlyingActor.Connection, com.ergodicity.cgate.Error))
      }
    }

    "notify engine on activated state" in {
      val engine = mockEngine.underlyingActor
      val services = mockServices.underlyingActor

      val manager = TestActorRef(new ConnectionManager(services, engine))
      manager ! CurrentState(manager.underlyingActor.Connection, com.ergodicity.cgate.Active)
      serviceTracker.expectMsg(ServiceStarted(ConnectionServiceId))
    }

    "stop itselt of Service.Stop message" in {
      val engine = mockEngine.underlyingActor
      val services = mockServices.underlyingActor

      val manager = TestActorRef(new ConnectionManager(services, engine))
      watch(manager)
      manager ! Service.Stop
      serviceTracker.expectMsg(ServiceStopped(ConnectionServiceId))
      expectMsg(Terminated(manager))
    }
  }
}