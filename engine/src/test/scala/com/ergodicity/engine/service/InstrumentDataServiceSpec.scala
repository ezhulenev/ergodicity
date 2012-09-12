package com.ergodicity.engine.service

import akka.actor.FSM.{CurrentState, Transition}
import akka.actor.{FSM, Terminated, ActorSystem}
import akka.event.Logging
import akka.testkit._
import com.ergodicity.cgate.DataStreamState
import com.ergodicity.cgate.config.Replication
import com.ergodicity.engine.Services
import com.ergodicity.engine.service.InstrumentDataState.Started
import com.ergodicity.engine.service.Service.Start
import com.ergodicity.engine.underlying.ListenerFactory
import org.mockito.Mockito._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{Connection => CGConnection, ISubscriber, Listener => CGListener}
import com.ergodicity.engine.Services.ServiceFailedException

class InstrumentDataServiceSpec extends TestKit(ActorSystem("InstrumentDataServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }


  implicit val Id = InstrumentData.InstrumentData

  val listenerFactory = new ListenerFactory {
    def apply(connection: CGConnection, config: String, subscriber: ISubscriber) = mock(classOf[CGListener])
  }

  "InstrumentData Service" must {
    "initialized in Idle state" in {
      val underlyingConnection = mock(classOf[CGConnection])
      val optInfoReplication = mock(classOf[Replication])
      val futInfoReplication = mock(classOf[Replication])

      implicit val services = mock(classOf[Services])

      val service = TestFSMRef(new InstrumentDataService(listenerFactory, underlyingConnection, futInfoReplication, optInfoReplication), "InstrumentDataService")

      assert(service.stateName == InstrumentDataState.Idle)
    }

    "start service" in {
      val underlyingConnection = mock(classOf[CGConnection])
      val optInfoReplication = mock(classOf[Replication])
      val futInfoReplication = mock(classOf[Replication])

      implicit val services = mock(classOf[Services])

      val service = TestFSMRef(new InstrumentDataService(listenerFactory, underlyingConnection, futInfoReplication, optInfoReplication), "InstrumentDataService")
      val underlying = service.underlyingActor.asInstanceOf[InstrumentDataService]

      when("got Start message")
      service ! Start

      then("should go to Starting state")
      assert(service.stateName == InstrumentDataState.Starting)

      when("both streams goes online")
      service ! CurrentState(underlying.FutInfoStream, DataStreamState.Online)
      service ! CurrentState(underlying.OptInfoStream, DataStreamState.Online)

      then("service shoud be started")
      assert(service.stateName == Started)
      and("Service Manager should be notified")
      verify(services).serviceStarted(InstrumentData.InstrumentData)
    }

    "stop service" in {
      val underlyingConnection = mock(classOf[CGConnection])
      val optInfoReplication = mock(classOf[Replication])
      val futInfoReplication = mock(classOf[Replication])

      implicit val services = mock(classOf[Services])

      given("service in Started state")
      val service = TestFSMRef(new InstrumentDataService(listenerFactory, underlyingConnection, futInfoReplication, optInfoReplication), "InstrumentDataService")
      val underlying = service.underlyingActor.asInstanceOf[InstrumentDataService]
      service.setState(InstrumentDataState.Started)

      when("stop Service")
      service ! Service.Stop

      then("should go to Stopping states")
      assert(service.stateName == InstrumentDataState.Stopping)

      when("both streams closed")
      service ! Transition(underlying.FutInfoStream, DataStreamState.Online, DataStreamState.Closed)
      service ! Transition(underlying.OptInfoStream, DataStreamState.Online, DataStreamState.Closed)

      then("service shoud be stopped")
      watch(service)
      expectMsg(Terminated(service))

      and("service Manager should be notified")
      verify(services).serviceStopped(InstrumentData.InstrumentData)
    }

    "fail starting" in {
      val underlyingConnection = mock(classOf[CGConnection])
      val optInfoReplication = mock(classOf[Replication])
      val futInfoReplication = mock(classOf[Replication])

      implicit val services = mock(classOf[Services])

      given("service in Starting state")
      val service = TestFSMRef(new InstrumentDataService(listenerFactory, underlyingConnection, futInfoReplication, optInfoReplication), "InstrumentDataService")
      service.setState(InstrumentDataState.Starting)

      when("starting timed out")
      then("should fail with exception")
      intercept[ServiceFailedException] {
        service receive FSM.StateTimeout
      }
    }

    "fail stopping" in {
      val underlyingConnection = mock(classOf[CGConnection])
      val optInfoReplication = mock(classOf[Replication])
      val futInfoReplication = mock(classOf[Replication])

      implicit val services = mock(classOf[Services])

      given("service in Stopping state")
      val service = TestFSMRef(new InstrumentDataService(listenerFactory, underlyingConnection, futInfoReplication, optInfoReplication), "InstrumentDataService")
      service.setState(InstrumentDataState.Stopping)

      when("stopping timed out")
      then("should fail with exception")
      intercept[ServiceFailedException] {
        service receive FSM.StateTimeout
      }
    }
  }
}