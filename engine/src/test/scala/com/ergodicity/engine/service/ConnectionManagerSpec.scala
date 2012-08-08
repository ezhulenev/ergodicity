package com.ergodicity.engine.service

import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import ru.micexrts.cgate.{Connection => CGConnection}
import org.mockito.Mockito._
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.engine.Engine

class ConnectionManagerSpec extends TestKit(ActorSystem("ConnectionManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  private def mockEngine(manager: TestProbe, connection: TestProbe) = TestActorRef(new Engine with Connection {
    val underlyingConnection = mock(classOf[CGConnection])
    val Connection = connection.ref

    val ServiceManager = manager.ref
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
      intercept[ConnectionException] {
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

    "notify engine on closed state" in {
      val connection = TestProbe()
      val serviceTracker = TestProbe()

      val engine = mockEngine(serviceTracker, connection).underlyingActor
      val manager = TestActorRef(new ConnectionManager(engine))
      manager ! Transition(connection.ref, com.ergodicity.cgate.Active, com.ergodicity.cgate.Closed)
      serviceTracker.expectMsg(ServiceStopped(ConnectionService))
    }
  }
}