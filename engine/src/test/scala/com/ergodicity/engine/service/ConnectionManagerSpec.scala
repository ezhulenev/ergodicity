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

class ConnectionManagerSpec extends TestKit(ActorSystem("ConnectionManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  private def mockEngine(manager: TestProbe) = TestActorRef(new Engine with Services with Strategies with UnderlyingConnection {
    val underlyingConnection = mock(classOf[CGConnection])

    val ServiceManager = manager.ref
    val StrategyEngine = system.deadLetters
  })

  "ConnectionManager" must {
    "throw exception on error state" in {
      val serviceTracker = TestProbe()

      val engine = mockEngine(serviceTracker).underlyingActor
      val manager = TestActorRef(new ConnectionManager(engine))
      intercept[ServiceFailedException] {
        manager.receive(CurrentState(manager.underlyingActor.Connection, com.ergodicity.cgate.Error))
      }
    }

    "notify engine on activated state" in {
      val serviceTracker = TestProbe()

      val engine = mockEngine(serviceTracker).underlyingActor
      val manager = TestActorRef(new ConnectionManager(engine))
      manager ! CurrentState(manager.underlyingActor.Connection, com.ergodicity.cgate.Active)
      serviceTracker.expectMsg(ServiceStarted(ConnectionServiceId))
    }

    "stop itselt of Service.Stop message" in {
      val serviceTracker = TestProbe()

      val engine = mockEngine(serviceTracker).underlyingActor
      val manager = TestActorRef(new ConnectionManager(engine))
      watch(manager)
      manager ! Service.Stop
      serviceTracker.expectMsg(ServiceStopped(ConnectionServiceId))
      expectMsg(Terminated(manager))
    }
  }
}