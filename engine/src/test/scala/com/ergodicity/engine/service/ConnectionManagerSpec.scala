package com.ergodicity.engine.service

import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}

class ConnectionManagerSpec extends TestKit(ActorSystem("ConnectionManagerSpec", com.ergodicity.engine.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "ConnectionManager" must {
    "subscribe for transitions" in {
      val connection = TestProbe()
      val engine = TestProbe()
      val watcher = TestActorRef(new ConnectionManager(engine.ref, connection.ref))
      connection.expectMsg(SubscribeTransitionCallBack(watcher))
    }

    "notify engine on error state" in {
      val connection = TestProbe()
      val engine = TestProbe()
      val watcher = TestActorRef(new ConnectionManager(engine.ref, connection.ref))
      watcher ! CurrentState(connection.ref, com.ergodicity.cgate.Error)
      engine.expectMsg(ServiceFailed(ConnectionService))
    }

    "notify engine on activated state" in {
      val connection = TestProbe()
      val engine = TestProbe()
      val watcher = TestActorRef(new ConnectionManager(engine.ref, connection.ref))
      watcher ! CurrentState(connection.ref, com.ergodicity.cgate.Active)
      engine.expectMsg(ServiceActivated(ConnectionService))
    }

    "notify engine on closed state" in {
      val connection = TestProbe()
      val engine = TestProbe()
      val watcher = TestActorRef(new ConnectionManager(engine.ref, connection.ref))
      watcher ! Transition(connection.ref, com.ergodicity.cgate.Active, com.ergodicity.cgate.Closed)
      engine.expectMsg(ServicePassivated(ConnectionService))
    }
  }
}