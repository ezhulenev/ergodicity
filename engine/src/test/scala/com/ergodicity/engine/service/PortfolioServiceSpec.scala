package com.ergodicity.engine.service

import akka.actor.FSM.Transition
import akka.actor._
import akka.event.Logging
import akka.testkit._
import com.ergodicity.cgate.DataStreamState
import com.ergodicity.cgate.config.Replication
import com.ergodicity.core.session.SessionActor.AssignedContents
import com.ergodicity.engine.Services
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.engine.underlying.ListenerFactory
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{Connection => CGConnection, ISubscriber, Listener => CGListener}

class PortfolioServiceSpec extends TestKit(ActorSystem("PortfolioServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val Id = Portfolio.Portfolio

  val listenerFactory = new ListenerFactory {
    def apply(connection: CGConnection, config: String, subscriber: ISubscriber) = mock(classOf[CGListener])
  }

  def createService(implicit services: Services = mock(classOf[Services])) = {
    val underlyingConnection = mock(classOf[CGConnection])
    val posReplication = mock(classOf[Replication])

    Mockito.when(services.service(InstrumentData.InstrumentData)).thenReturn(system.deadLetters)

    TestFSMRef(new PortfolioService(listenerFactory, underlyingConnection, posReplication), "Portfolio")
  }

  "Portfolio Service" must {
    "initizlied in idle state" in {
      val service = createService()
      assert(service.stateName == PortfolioState.Idle)
    }

    "start service" in {
      val services = mock(classOf[Services])
      val service = createService(services)
      val underlying = service.underlyingActor.asInstanceOf[PortfolioService]

      when("receive start message")
      service ! Start
      then("should wait for ongoing session & assigned contents")
      assert(service.stateName == PortfolioState.AssigningInstruments)

      when("got assigned contents")
      service ! AssignedContents(Set())
      then("should ask for assigned contents")
      and("go to Starting state")
      Thread.sleep(100)
      assert(service.stateName == PortfolioState.StartingPositionsTracker)

      when("Pos data stream goes online")
      service ! Transition(underlying.PosStream, DataStreamState.Closed, DataStreamState.Online)

      then("service shoud be started")
      assert(service.stateName == PortfolioState.Started)
      and("Service Manager should be notified")
      verify(services).serviceStarted(Portfolio.Portfolio)
    }

    "stop service" in {
      val services = mock(classOf[Services])
      val service = createService(services)
      service.setState(PortfolioState.Started)
      val underlying = service.underlyingActor.asInstanceOf[PortfolioService]

      when("receive Stop message")
      service ! Stop
      then("should go to Stopping state")
      assert(service.stateName == PortfolioState.Stopping)

      when("Pos stream closed")
      service ! Transition(underlying.PosStream, DataStreamState.Online, DataStreamState.Closed)

      then("service shoud be stopped")
      watch(service)
      expectMsg(Terminated(service))

      and("service Manager should be notified")
      verify(services).serviceStopped(Portfolio.Portfolio)
    }
  }
}