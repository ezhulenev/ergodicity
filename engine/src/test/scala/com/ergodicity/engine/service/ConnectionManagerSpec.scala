package com.ergodicity.engine.service

import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import com.ergodicity.engine.MockedEngine
import ru.micexrts.cgate.{Connection => CGConnection}
import org.mockito.Mockito._
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}

class ConnectionManagerSpec extends TestKit(ActorSystem("ConnectionManagerSpec", com.ergodicity.engine.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  private def mockEngine(serviceTracker: TestProbe, connection: TestProbe) = new MockedEngine(log, serviceTracker.ref) with Connection {
    val underlyingConnection = mock(classOf[CGConnection])
    val Connection = connection.ref
  }

  "ConnectionManager" must {
    "subscribe for transitions" in {
      val connection = TestProbe()
      val serviceTracker = TestProbe()

      val engine = mockEngine(serviceTracker, connection)
      val watcher = TestActorRef(new ConnectionManager(engine))
      connection.expectMsg(SubscribeTransitionCallBack(watcher))
    }

    "throw exception on error state" in {
      val connection = TestProbe()
      val serviceTracker = TestProbe()

      val engine = mockEngine(serviceTracker, connection)
      val watcher = TestActorRef(new ConnectionManager(engine))
      intercept[ConnectionException] {
        watcher.receive(CurrentState(connection.ref, com.ergodicity.cgate.Error))
      }
    }

    "notify engine on activated state" in {
      val connection = TestProbe()
      val serviceTracker = TestProbe()

      val engine = mockEngine(serviceTracker, connection)
      val watcher = TestActorRef(new ConnectionManager(engine))
      watcher ! CurrentState(connection.ref, com.ergodicity.cgate.Active)
      serviceTracker.expectMsg(ServiceActivated(ConnectionService))
    }

    "notify engine on closed state" in {
      val connection = TestProbe()
      val serviceTracker = TestProbe()

      val engine = mockEngine(serviceTracker, connection)
      val watcher = TestActorRef(new ConnectionManager(engine))
      watcher ! Transition(connection.ref, com.ergodicity.cgate.Active, com.ergodicity.cgate.Closed)
      serviceTracker.expectMsg(ServicePassivated(ConnectionService))
    }
  }
}