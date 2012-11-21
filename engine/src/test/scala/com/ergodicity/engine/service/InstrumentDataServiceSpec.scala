package com.ergodicity.engine.service

import akka.actor.FSM.{CurrentState, Transition}
import akka.actor.{FSM, Terminated, ActorSystem}
import akka.event.Logging
import akka.testkit._
import com.ergodicity.cgate.{ListenerDecorator, DataStreamState}
import com.ergodicity.engine.Services
import com.ergodicity.engine.Services.ServiceFailedException
import com.ergodicity.engine.service.InstrumentDataState.Started
import com.ergodicity.engine.service.Service.Start
import org.mockito.Mockito._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import org.mockito.Mockito
import ru.micexrts.cgate.{Listener => CGListener}

class InstrumentDataServiceSpec extends TestKit(ActorSystem("InstrumentDataServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val Id = InstrumentData.InstrumentData

  val futInfoListener = mock(classOf[ListenerDecorator])
  Mockito.when(futInfoListener.listener).thenReturn(mock(classOf[CGListener]))
  val optInfoListener = mock(classOf[ListenerDecorator])
  Mockito.when(optInfoListener.listener).thenReturn(mock(classOf[CGListener]))


  "InstrumentData Service" must {
    "initialized in Idle state" in {
    implicit val services = mock(classOf[Services])

      val service = TestFSMRef(new InstrumentDataService(futInfoListener, optInfoListener), "InstrumentDataService")

      assert(service.stateName == InstrumentDataState.Idle)
    }

    "start service" in {
      implicit val services = mock(classOf[Services])

      val service = TestFSMRef(new InstrumentDataService(futInfoListener, optInfoListener), "InstrumentDataService")
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
      Thread.sleep(700) // Notification delayed
      verify(services).serviceStarted(InstrumentData.InstrumentData)
    }

    "stop service" in {
      implicit val services = mock(classOf[Services])

      given("service in Started state")
      val service = TestFSMRef(new InstrumentDataService(futInfoListener, optInfoListener), "InstrumentDataService")
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
      implicit val services = mock(classOf[Services])

      given("service in Starting state")
      val service = TestFSMRef(new InstrumentDataService(futInfoListener, optInfoListener), "InstrumentDataService")
      service.setState(InstrumentDataState.Starting)

      when("starting timed out")
      then("should fail with exception")
      intercept[ServiceFailedException] {
        service receive FSM.StateTimeout
      }
    }

    "fail stopping" in {
      implicit val services = mock(classOf[Services])

      given("service in Stopping state")
      val service = TestFSMRef(new InstrumentDataService(futInfoListener, optInfoListener), "InstrumentDataService")
      service.setState(InstrumentDataState.Stopping)

      when("stopping timed out")
      then("should fail with exception")
      intercept[ServiceFailedException] {
        service receive FSM.StateTimeout
      }
    }
  }
}