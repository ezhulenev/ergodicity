package com.ergodicity.engine.service

import akka.actor.{Terminated, ActorSystem}
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import org.mockito.Mockito._
import com.ergodicity.cgate.config.Replication
import com.ergodicity.engine.underlying.ListenerFactory
import com.ergodicity.engine.Services
import com.ergodicity.engine.service.Service.Start
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import com.ergodicity.core.SessionsTrackingState
import ru.micexrts.cgate
import cgate.{Connection => CGConnection, ISubscriber, Listener => CGListener}

class InstrumentDataServiceSpec extends TestKit(ActorSystem("InstrumentDataServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }


  implicit val Id = InstrumentData.InstrumentData

  val listenerFactory = new ListenerFactory {
    def apply(connection: cgate.Connection, config: String, subscriber: ISubscriber) = mock(classOf[CGListener])
  }

  "InstrumentData Service" must {
    "stash messages before ConnectionService is activated" in {
      val underlyingConnection = mock(classOf[CGConnection])
      val optInfoReplication = mock(classOf[Replication])
      val futInfoReplication = mock(classOf[Replication])

      implicit val services = mock(classOf[Services])
      val sessions = TestProbe()

      val service = TestActorRef(new InstrumentDataService(listenerFactory, underlyingConnection, futInfoReplication, optInfoReplication) {
        override val Sessions = sessions.ref
      }, "InstrumentDataService")

      when("got Start message")
      service ! Start

      then("should subscribe Sessions state")
      sessions.expectMsg(SubscribeTransitionCallBack(service))

      when("Sessions goes online")
      service ! Transition(sessions.ref, SessionsTrackingState.Binded, SessionsTrackingState.Online)

      then("Service Manager should be notified")
      verify(services).serviceStarted(InstrumentData.InstrumentData)
    }

    "stop actor on Service.Stop message" in {
      val underlyingConnection = mock(classOf[CGConnection])
      val optInfoReplication = mock(classOf[Replication])
      val futInfoReplication = mock(classOf[Replication])

      implicit val services = mock(classOf[Services])
      val sessions = TestProbe()

      val service = TestActorRef(new InstrumentDataService(listenerFactory, underlyingConnection, futInfoReplication, optInfoReplication) {
        override val Sessions = sessions.ref
      }, "InstrumentDataService")

      when("stop Service")
      service ! Service.Stop

      then("sessions manager actor terminated")
      watch(service)
      expectMsg(Terminated(service))

      and("service manager should be notified")
      verify(services).serviceStopped(InstrumentData.InstrumentData)
    }
  }
}