package com.ergodicity.engine.service

import akka.actor.FSM.Transition
import akka.actor.{FSM, Terminated, ActorSystem}
import akka.event.Logging
import akka.testkit._
import com.ergodicity.cgate.DataStreamState
import com.ergodicity.cgate.config.{ListenerConfig, Replication}
import com.ergodicity.engine.Services
import com.ergodicity.engine.service.Service.Start
import com.ergodicity.engine.underlying.ListenerFactory
import org.mockito.Mockito._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{Connection => CGConnection, ISubscriber, Listener => CGListener}
import com.ergodicity.engine.Services.ServiceFailedException
import org.mockito.Mockito
import com.ergodicity.core.session.SessionActor.AssignedContents

class TradesDataServiceSpec extends TestKit(ActorSystem("TradesDataServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val Id = TradesData.TradesData

  val listenerFactory = new ListenerFactory {
    def apply(connection: CGConnection, config: ListenerConfig, subscriber: ISubscriber) = mock(classOf[CGListener])
  }

  def createService(implicit services: Services = mock(classOf[Services])) = {
    val underlyingConnection = mock(classOf[CGConnection])
    val futTradeReplication = mock(classOf[Replication])
    val optTradeReplication = mock(classOf[Replication])

    Mockito.when(services.service(InstrumentData.InstrumentData)).thenReturn(system.deadLetters)

    TestFSMRef(new TradesDataService(listenerFactory, underlyingConnection, futTradeReplication, optTradeReplication), "TradesDataService")
  }


  "InstrumentData Service" must {
    "initialized in Idle state" in {
      val service = createService
      assert(service.stateName == TradesDataState.Idle)
    }

    "start service" in {
      implicit val services = mock(classOf[Services])

      val service = createService
      val underlying = service.underlyingActor.asInstanceOf[TradesDataService]

      when("receive start message")
      service ! Start
      then("should wait for ongoing session & assigned contents")
      assert(service.stateName == TradesDataState.AssigningInstruments)

      when("got assigned contents")
      service ! AssignedContents(Set())
      then("go to Starting state")
      Thread.sleep(100)
      assert(service.stateName == TradesDataState.StartingTradesTracker)

      when("Fut/OptTrade data stream goes online")
      service ! Transition(underlying.FutTradeStream, DataStreamState.Closed, DataStreamState.Online)
      service ! Transition(underlying.OptTradeStream, DataStreamState.Closed, DataStreamState.Online)

      then("service shoud be started")
      assert(service.stateName == TradesDataState.Started)
      and("Service Manager should be notified")
      verify(services).serviceStarted(Id)
    }

    "stop service" in {
      implicit val services = mock(classOf[Services])

      given("service in Started state")
      val service = createService
      val underlying = service.underlyingActor.asInstanceOf[TradesDataService]
      service.setState(TradesDataState.Started)

      when("stop Service")
      service ! Service.Stop

      then("should go to Stopping states")
      assert(service.stateName == TradesDataState.Stopping)

      when("both streams closed")
      service ! Transition(underlying.FutTradeStream, DataStreamState.Online, DataStreamState.Closed)
      service ! Transition(underlying.OptTradeStream, DataStreamState.Online, DataStreamState.Closed)

      then("service shoud be stopped")
      watch(service)
      expectMsg(Terminated(service))

      and("service Manager should be notified")
      verify(services).serviceStopped(Id)
    }

    "fail starting" in {
      given("service in Starting state")
      val service = createService
      service.setState(TradesDataState.StartingTradesTracker)

      when("starting timed out")
      then("should fail with exception")
      intercept[ServiceFailedException] {
        service receive FSM.StateTimeout
      }
    }

    "fail stopping" in {
      given("service in Stopping state")
      val service = createService
      service.setState(TradesDataState.Stopping)

      when("stopping timed out")
      then("should fail with exception")
      intercept[ServiceFailedException] {
        service receive FSM.StateTimeout
      }
    }
  }
}