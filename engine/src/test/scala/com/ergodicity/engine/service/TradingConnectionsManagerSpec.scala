package com.ergodicity.engine.service

import akka.actor.{Terminated, ActorSystem}
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import ru.micexrts.cgate.{Connection => CGConnection}
import org.mockito.Mockito._
import akka.actor.FSM.CurrentState
import com.ergodicity.engine.{Strategies, Services, ServiceFailedException, Engine}
import com.ergodicity.engine.underlying.UnderlyingTradingConnections
import com.ergodicity.cgate.ConnectionState
import org.mockito.Mockito

class TradingConnectionsManagerSpec extends TestKit(ActorSystem("TradingConnectionsManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  private def mockEngine(manager: TestProbe, publisherConnection: CGConnection, repliesConnection: CGConnection) = TestActorRef(new Engine with Services with Strategies with UnderlyingTradingConnections {
    val ServiceManager = manager.ref

    val StrategyEngine = system.deadLetters

    val underlyingPublisherConnection = publisherConnection

    val underlyingRepliesConnection = repliesConnection
  })

  "TradingConnectionsManager" must {
    "open connections on Service.Start" in {
      val publisherConnection = mock(classOf[CGConnection])
      val repliesConnection = mock(classOf[CGConnection])
      val serviceManager = TestProbe()

      val engine = mockEngine(serviceManager, publisherConnection, repliesConnection).underlyingActor
      val manager = TestFSMRef(new TradingConnectionsManager(engine), "Manager")

      assert(manager.stateName == TradingConnectionsManager.Idle)
      when("service started")
      manager ! Service.Start

      // Let messages to be passed to Connection actor
      Thread.sleep(300)

      then("should open connections")
      verify(publisherConnection).open("")
      verify(repliesConnection).open("")
    }

    "throw exception on publisher connection go to error state" in {
      val publisherConnection = mock(classOf[CGConnection])
      val repliesConnection = mock(classOf[CGConnection])
      val serviceManager = TestProbe()

      val engine = mockEngine(serviceManager, publisherConnection, repliesConnection).underlyingActor
      val manager = TestActorRef(new TradingConnectionsManager(engine), "Manager")
      val underlying = manager.underlyingActor

      intercept[ServiceFailedException] {
        manager.receive(CurrentState(underlying.PublisherConnection, com.ergodicity.cgate.Error))
      }
    }

    "notify engine on both connections activated" in {
      val publisherConnection = mock(classOf[CGConnection])
      val repliesConnection = mock(classOf[CGConnection])
      val serviceManager = TestProbe()

      val engine = mockEngine(serviceManager, publisherConnection, repliesConnection).underlyingActor
      val manager = TestFSMRef(new TradingConnectionsManager(engine), "Manager")
      val underlying = manager.underlyingActor.asInstanceOf[TradingConnectionsManager]


      manager ! Service.Start
      assert(manager.stateName == TradingConnectionsManager.Starting)

      manager ! CurrentState(underlying.PublisherConnection, com.ergodicity.cgate.Active)
      manager ! CurrentState(underlying.RepliesConnection, com.ergodicity.cgate.Active)

      serviceManager.expectMsg(ServiceStarted(TradingConnectionsServiceId))
      assert(manager.stateName == TradingConnectionsManager.Connected)
    }

    "stop itselt of Service.Stop message" in {
      val publisherConnection = mock(classOf[CGConnection])
      val repliesConnection = mock(classOf[CGConnection])
      val serviceManager = TestProbe()

      val engine = mockEngine(serviceManager, publisherConnection, repliesConnection).underlyingActor
      val manager = TestFSMRef(new TradingConnectionsManager(engine), "Manager")
      val underlying = manager.underlyingActor.asInstanceOf[TradingConnectionsManager]

      // Activate connections
      Mockito.when(publisherConnection.getState).thenReturn(ru.micexrts.cgate.State.ACTIVE)
      Mockito.when(repliesConnection.getState).thenReturn(ru.micexrts.cgate.State.ACTIVE)
      underlying.PublisherConnection ! ConnectionState(com.ergodicity.cgate.Active)
      underlying.RepliesConnection ! ConnectionState(com.ergodicity.cgate.Active)

      // Set manager state to Connected
      manager.setState(TradingConnectionsManager.Connected)

      watch(manager)
      manager ! Service.Stop

      // Verify manager stopped
      serviceManager.expectMsg(ServiceStopped(TradingConnectionsServiceId))
      expectMsg(Terminated(manager))

      // Verify connections closed
      verify(repliesConnection).close()
      verify(repliesConnection).dispose()
      verify(publisherConnection).close()
      verify(publisherConnection).dispose()
    }
  }
}