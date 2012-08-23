package com.ergodicity.engine.service

import akka.actor.{Terminated, ActorSystem}
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import ru.micexrts.cgate.{Connection => CGConnection}
import org.mockito.Mockito._
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.engine.{ServiceFailedException, Engine}
import com.ergodicity.cgate.{Connection => ErgodicityConnection}

class TradingConnectionsManagerSpec extends TestKit(ActorSystem("TradingConnectionsManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  private def mockEngine(manager: TestProbe, publisherConnection: TestProbe, repliesConnection: TestProbe) = TestActorRef(new Engine with TradingConnections {
    val ServiceManager = manager.ref

    val StrategyManager = system.deadLetters

    def underlyingPublisherConnection = mock(classOf[CGConnection])

    def underlyingRepliesConnection = mock(classOf[CGConnection])

    def PublisherConnection = publisherConnection.ref

    def RepliesConnection = repliesConnection.ref
  })

  "TradingConnectionsManager" must {
    "subscribe for transitions on Service.Start" in {
      val publisherConnection = TestProbe()
      val repliesConnection = TestProbe()
      val serviceManager = TestProbe()

      val engine = mockEngine(serviceManager, publisherConnection, repliesConnection).underlyingActor
      val manager = TestFSMRef(new TradingConnectionsManager(engine), "Manager")

      assert(manager.stateName == TradingConnectionsManager.Idle)
      when("service started")
      manager ! Service.Start

      then("should subscribe for connections states")
      publisherConnection.expectMsg(SubscribeTransitionCallBack(manager))
      repliesConnection.expectMsg(SubscribeTransitionCallBack(manager))

      and("open connections")
      publisherConnection.expectMsg(ErgodicityConnection.Open)
      repliesConnection.expectMsg(ErgodicityConnection.Open)
    }

    "throw exception on publisher connection go to error state" in {
      val publisherConnection = TestProbe()
      val repliesConnection = TestProbe()
      val serviceManager = TestProbe()

      val engine = mockEngine(serviceManager, publisherConnection, repliesConnection).underlyingActor
      val manager = TestFSMRef(new TradingConnectionsManager(engine), "Manager")

      intercept[ServiceFailedException] {
        manager.receive(CurrentState(publisherConnection.ref, com.ergodicity.cgate.Error))
      }
    }

    "notify engine on both connections activated" in {
      val publisherConnection = TestProbe()
      val repliesConnection = TestProbe()
      val serviceManager = TestProbe()

      val engine = mockEngine(serviceManager, publisherConnection, repliesConnection).underlyingActor
      val manager = TestFSMRef(new TradingConnectionsManager(engine), "Manager")

      manager ! Service.Start
      assert(manager.stateName == TradingConnectionsManager.Starting)

      manager ! CurrentState(publisherConnection.ref, com.ergodicity.cgate.Active)
      manager ! CurrentState(repliesConnection.ref, com.ergodicity.cgate.Active)

      serviceManager.expectMsg(ServiceStarted(TradingConnectionsServiceId))
      assert(manager.stateName == TradingConnectionsManager.Connected)
    }

    "stop itselt of Service.Stop message" in {
      val publisherConnection = TestProbe()
      val repliesConnection = TestProbe()
      val serviceManager = TestProbe()

      val engine = mockEngine(serviceManager, publisherConnection, repliesConnection).underlyingActor
      val manager = TestFSMRef(new TradingConnectionsManager(engine), "Manager")

      manager.setState(TradingConnectionsManager.Connected)
      watch(manager)
      manager ! Service.Stop
      serviceManager.expectMsg(ServiceStopped(TradingConnectionsServiceId))
      expectMsg(Terminated(manager))
    }
  }
}