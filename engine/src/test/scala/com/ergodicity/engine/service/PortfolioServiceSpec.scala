package com.ergodicity.engine.service

import akka.actor.{Terminated, Props, ActorSystem}
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.util.duration._
import akka.testkit._
import org.mockito.Mockito._
import com.ergodicity.cgate.config.Replication
import com.ergodicity.engine.Services
import com.ergodicity.engine.underlying.ListenerFactory
import ru.micexrts.cgate.{Connection => CGConnection, ISubscriber, Listener => CGListener}
import com.ergodicity.engine.service.Service.Start
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import com.ergodicity.core.PositionsTrackingState

class PortfolioServiceSpec extends TestKit(ActorSystem("PortfolioServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val Id = Portfolio.Portfolio

  val listenerFactory = new ListenerFactory {
    def apply(connection: CGConnection, config: String, subscriber: ISubscriber) = mock(classOf[CGListener])
  }

  "Portfolio Service" must {
    "stash messages before ConnectionService is activated" in {
      val underlyingConnection = mock(classOf[CGConnection])
      val posReplication = mock(classOf[Replication])


      implicit val services = mock(classOf[Services])
      val positions = TestProbe()

      val service = TestActorRef(Props(new PortfolioService(listenerFactory, underlyingConnection, posReplication) {
        override val Positions = positions.ref
      }), "Portfolio")

      when("got Start message")
      service ! Start

      then("should track Positions state")
      positions.expectMsg(SubscribeTransitionCallBack(service))

      when("Positions goes online")
      service ! Transition(positions.ref, PositionsTrackingState.Binded, PositionsTrackingState.Online)

      then("Service Manager should be notified")
      verify(services).serviceStarted(Id)
    }

    "stop actor on Service.Stop message" in {
      val underlyingConnection = mock(classOf[CGConnection])
      val posReplication = mock(classOf[Replication])

      implicit val services = mock(classOf[Services])
      val positions = TestProbe()

      val service = TestActorRef(Props(new PortfolioService(listenerFactory, underlyingConnection, posReplication) {
        override val Positions = positions.ref
      }), "Portfolio")

      when("stop Service")
      service ! Service.Stop

      then("positions manager actor terminated")
      watch(service)
      expectMsg(Terminated(service))

      and("service manager should be notified")
      verify(services).serviceStopped
    }
  }
}