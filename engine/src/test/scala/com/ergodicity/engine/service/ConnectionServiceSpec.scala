package com.ergodicity.engine.service

import akka.actor.{Props, Terminated, ActorSystem}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import ru.micexrts.cgate.{Connection => CGConnection}
import org.mockito.Mockito._
import akka.actor.FSM.CurrentState
import com.ergodicity.engine.Services.ServiceFailedException
import com.ergodicity.engine.Services

class ConnectionServiceSpec extends TestKit(ActorSystem("ConnectionServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "ConnectionService" must {
    "throw exception on error state" in {
      val underlyingConnection = mock(classOf[CGConnection])
      implicit val services = mock(classOf[Services])
      implicit val Id = ReplicationConnection.Connection

      val service = TestActorRef(new ConnectionService(underlyingConnection), "ConnectionService")
      intercept[ServiceFailedException] {
        service.receive(CurrentState(service.underlyingActor.Connection, com.ergodicity.cgate.Error))
      }
    }

    "notify engine on activated state" in {
      val underlyingConnection = mock(classOf[CGConnection])
      implicit val services = mock(classOf[Services])
      implicit val Id = ReplicationConnection.Connection

      val service = TestActorRef(new ConnectionService(underlyingConnection), "ConnectionService")
      service ! CurrentState(service.underlyingActor.Connection, com.ergodicity.cgate.Active)

      verify(services).serviceStarted(Id)
    }

    "stop itselt of Service.Stop message" in {
      val underlyingConnection = mock(classOf[CGConnection])
      implicit val services = mock(classOf[Services])
      implicit val Id = ReplicationConnection.Connection

      // CallingThreadDispatcher for TestActorRef breaks test
      val service = system.actorOf(Props(new ConnectionService(underlyingConnection)))
      watch(service)
      service ! Service.Stop

      Thread.sleep(1100)

      verify(services).serviceStopped(Id)
      expectMsg(Terminated(service))
    }
  }
}