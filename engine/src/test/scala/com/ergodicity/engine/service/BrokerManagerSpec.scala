package com.ergodicity.engine.service

import akka.actor.ActorSystem
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._

class BrokerManagerSpec extends TestKit(ActorSystem("BrokerManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

/*  private def mockEngine(serviceManager: TestProbe) = TestActorRef(new {
    val ServiceManager = serviceManager.ref
    val StrategyEngine = system.deadLetters
  } with Engine with Services with Strategies with CreateListener with UnderlyingTradingConnections with UnderlyingPublisher with TradingService {
    val BrokerName = "TestBroker"

    def underlyingPublisherConnection = mock(classOf[CGConnection])

    def underlyingRepliesConnection = mock(classOf[CGConnection])

    implicit def BrokerConfig = null

    def underlyingPublisher = mock(classOf[CGPublisher])

    def listener(connection: CGConnection, config: String, subscriber: ISubscriber) = mock(classOf[CGListener])
  })


  "Trading Manager" must {
    "stash messages before BrokerConnectionsService is activated" in {
      val serviceManager = TestProbe()
      val broker = TestProbe()

      val engine = mockEngine(serviceManager).underlyingActor
      val manager: ActorRef = TestActorRef(Props(new BrokerManager(engine) {
        override val Broker = broker.ref
      }).withDispatcher("deque-dispatcher"), "BrokerManager")

      when("got Start message before broker connections service started")
      manager ! Start
      then("should stash it")
      broker.expectNoMsg(300.millis)

      when("Trading Connection Service started")
      manager ! ServiceStarted(TradingConnectionsServiceId)

      then("should track Trading state")
      broker.expectMsg(SubscribeTransitionCallBack(manager))

      when("Trading activated")
      manager ! Transition(broker.ref, Opening, Active)

      then("Service Manager should be notified")
      serviceManager.expectMsg(ServiceStarted(TradingServiceId))
    }

    "stop actor on Service.Stop message" in {
      val serviceManager = TestProbe()
      val broker = TestProbe()

      val engine = mockEngine(serviceManager).underlyingActor
      val manager: ActorRef = TestActorRef(Props(new BrokerManager(engine) {
        override val Broker = broker.ref
      }).withDispatcher("deque-dispatcher"), "BrokerManager")

      manager ! ServiceStarted(TradingConnectionsServiceId)
      watch(manager)

      when("stop Service")
      manager ! Service.Stop

      when("service manager should be notified")
      serviceManager.expectMsg(ServiceStopped(TradingServiceId))

      and("broker manager actor terminated")
      expectMsg(Terminated(manager))
    }
  }*/
}