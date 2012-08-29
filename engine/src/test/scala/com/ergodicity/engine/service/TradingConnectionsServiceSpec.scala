package com.ergodicity.engine.service

import akka.actor.{ActorRef, Props, Terminated, ActorSystem}
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import org.mockito.Mockito._
import ru.micexrts.cgate.{Connection => CGConnection}
import com.ergodicity.engine.Services.{Reporter, ServiceFailedException}
import akka.actor.FSM.CurrentState
import com.ergodicity.cgate.ConnectionState
import org.mockito.Mockito

class TradingConnectionsServiceSpec extends TestKit(ActorSystem("TradingConnectionsServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val Id = TradingConnections.TradingConnections

  "TradingConnectionsService" must {
    "open connections on Service.Start" in {
      val publisherConnection = mock(classOf[CGConnection])
      val repliesConnection = mock(classOf[CGConnection])
      implicit val reporter = mock(classOf[Reporter])

      val service = TestFSMRef(new TradingConnectionsService(publisherConnection, repliesConnection), "TradingConnectionsService")

      assert(service.stateName == TradingConnectionsService.Idle)
      when("service started")
      service ! Service.Start

      // Let messages to be passed to Connection actor
      Thread.sleep(300)

      then("should open connections")
      verify(publisherConnection).open("")
      verify(repliesConnection).open("")
    }

    "throw exception on publisher connection go to error state" in {
      val publisherConnection = mock(classOf[CGConnection])
      val repliesConnection = mock(classOf[CGConnection])
      implicit val reporter = mock(classOf[Reporter])

      val service = TestActorRef(new TradingConnectionsService(publisherConnection, repliesConnection), "TradingConnectionsService")
      val underlying = service.underlyingActor

      intercept[ServiceFailedException] {
        service.receive(CurrentState(underlying.PublisherConnection, com.ergodicity.cgate.Error))
      }
    }

    "notify engine on both connections activated" in {
      val publisherConnection = mock(classOf[CGConnection])
      val repliesConnection = mock(classOf[CGConnection])
      implicit val reporter = mock(classOf[Reporter])

      val service = TestFSMRef(new TradingConnectionsService(publisherConnection, repliesConnection), "TradingConnectionsService")
      val underlying = service.underlyingActor.asInstanceOf[TradingConnectionsService]

      service ! Service.Start
      assert(service.stateName == TradingConnectionsService.Starting)

      service ! CurrentState(underlying.PublisherConnection, com.ergodicity.cgate.Active)
      service ! CurrentState(underlying.RepliesConnection, com.ergodicity.cgate.Active)

      Thread.sleep(300)

      verify(reporter).serviceStarted(Id)
      assert(service.stateName == TradingConnectionsService.Connected)
    }

    "stop itselt of Service.Stop message" in {
      val publisherConnection = mock(classOf[CGConnection])
      val repliesConnection = mock(classOf[CGConnection])
      implicit val reporter = mock(classOf[Reporter])

      Mockito.when(publisherConnection.getState).thenReturn(ru.micexrts.cgate.State.ACTIVE)
      Mockito.when(repliesConnection.getState).thenReturn(ru.micexrts.cgate.State.ACTIVE)

      var PubConn: ActorRef = null
      var RepConn: ActorRef = null

      // CallingThreadDispatcher for TestActorRef breaks test
      val service = system.actorOf(Props(new TradingConnectionsService(publisherConnection, repliesConnection) {
        PubConn = PublisherConnection
        RepConn = RepliesConnection
      }))

      Thread.sleep(50)

      service ! Service.Start

      // Activate connections
      PubConn ! ConnectionState(com.ergodicity.cgate.Active)
      RepConn ! ConnectionState(com.ergodicity.cgate.Active)

      // Set manager state to Connected through sending messages
      service ! CurrentState(PubConn, com.ergodicity.cgate.Active)
      service ! CurrentState(RepConn, com.ergodicity.cgate.Active)

      watch(service)
      when("receive Stop event")
      service ! Service.Stop

      // Let service to stop all connections
      Thread.sleep(300)

      // Verify manager stopped
      then("should stop service")
      verify(reporter).serviceStopped(Id)
      expectMsg(Terminated(service))

      and("close and dispose connections")
      // Verify connections closed
      verify(repliesConnection).close()
      verify(repliesConnection).dispose()
      verify(publisherConnection).close()
      verify(publisherConnection).dispose()
    }
  }
}