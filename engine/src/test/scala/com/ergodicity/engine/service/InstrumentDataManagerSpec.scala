package com.ergodicity.engine.service

import akka.actor.ActorSystem
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._

class InstrumentDataManagerSpec extends TestKit(ActorSystem("InstrumentDataManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  /*private def mockEngine(serviceManager: TestProbe) = TestActorRef(new {
    val ServiceManager = serviceManager.ref
    val StrategyEngine = system.deadLetters

  } with Engine with Services with Strategies with UnderlyingConnection with CreateListener with FutInfoReplication with OptInfoReplication {
    val underlyingConnection = mock(classOf[CGConnection])

    val optInfoReplication = mock(classOf[Replication])
    val futInfoReplication = mock(classOf[Replication])

    def listener(connection: CGConnection, config: String, subscriber: ISubscriber) = mock(classOf[CGListener])
  })

  "InstrumentData Manager" must {
    "stash messages before ConnectionService is activated" in {
      val serviceManager = TestProbe()
      val sessions = TestProbe()

      val engine = mockEngine(serviceManager).underlyingActor

      System.out.println("Engine = "+engine+", conn = "+engine.underlyingConnection)

      val manager: ActorRef = TestActorRef(Props(new InstrumentDataManager(engine) {
        override val Sessions = sessions.ref
      }).withDispatcher("deque-dispatcher"), "InstrumentDataManager")

      when("got Start message before connection service started")
      manager ! Start
      then("should stash it")
      sessions.expectNoMsg(300.millis)

      when("Connection Service started")
      manager ! ServiceStarted(ConnectionServiceId)

      then("should track InstrumentData state")
      sessions.expectMsg(SubscribeTransitionCallBack(manager))

      when("InstrumentData goes online")
      manager ! Transition(sessions.ref, SessionsTrackingState.Binded, SessionsTrackingState.Online)

      then("Service Manager should be notified")
      serviceManager.expectMsg(ServiceStarted(InstrumentDataServiceId))
    }

    "stop actor on Service.Stop message" in {
      val serviceManager = TestProbe()
      val sessions = TestProbe()

      val engine = mockEngine(serviceManager).underlyingActor
      val manager: ActorRef = TestActorRef(Props(new InstrumentDataManager(engine) {
        override val Sessions = sessions.ref
      }).withDispatcher("deque-dispatcher"), "InstrumentDataManager")

      manager ! ServiceStarted(ConnectionServiceId)
      watch(manager)

      when("stop Service")
      manager ! Service.Stop

      when("service manager should be notified")
      serviceManager.expectMsg(ServiceStopped(InstrumentDataServiceId))

      and("sessions manager actor terminated")
      expectMsg(Terminated(manager))
    }
  }*/
}