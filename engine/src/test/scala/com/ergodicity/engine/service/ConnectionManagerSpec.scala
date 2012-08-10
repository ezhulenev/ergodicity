package com.ergodicity.engine.service

import akka.actor.{Terminated, ActorSystem}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import ru.micexrts.cgate.{Connection => CGConnection}
import org.mockito.Mockito._
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.engine.{ServiceFailedException, Engine}

class ConnectionManagerSpec extends TestKit(ActorSystem("ConnectionManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  private def mockEngine(manager: TestProbe, connection: TestProbe) = TestActorRef(new Engine with Connection {
    val underlyingConnection = mock(classOf[CGConnection])
    val Connection = connection.ref

    val ServiceManager = manager.ref
    val StrategyManager = system.deadLetters
  })

  "ConnectionManager" must {
    "subscribe for transitions" in {
      val connection = TestProbe()
      val serviceTracker = TestProbe()

      val engine = mockEngine(serviceTracker, connection).underlyingActor
      val manager = TestActorRef(new ConnectionManager(engine))
      connection.expectMsg(SubscribeTransitionCallBack(manager))
    }

    "throw exception on error state" in {
      val connection = TestProbe()
      val serviceTracker = TestProbe()

      val engine = mockEngine(serviceTracker, connection).underlyingActor
      val manager = TestActorRef(new ConnectionManager(engine))
      intercept[ServiceFailedException] {
        manager.receive(CurrentState(connection.ref, com.ergodicity.cgate.Error))
      }
    }

    "notify engine on activated state" in {
      val connection = TestProbe()
      val serviceTracker = TestProbe()

      val engine = mockEngine(serviceTracker, connection).underlyingActor
      val manager = TestActorRef(new ConnectionManager(engine))
      manager ! CurrentState(connection.ref, com.ergodicity.cgate.Active)
      serviceTracker.expectMsg(ServiceStarted(ConnectionService))
    }

    "stop itselt of Service.Stop message" in {
      val connection = TestProbe()
      val serviceTracker = TestProbe()

      val engine = mockEngine(serviceTracker, connection).underlyingActor
      val manager = TestActorRef(new ConnectionManager(engine))
      watch(manager)
      manager ! Service.Stop
      serviceTracker.expectMsg(ServiceStopped(ConnectionService))
      expectMsg(Terminated(manager))
    }
  }
}