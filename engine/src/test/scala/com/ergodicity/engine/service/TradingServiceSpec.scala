package com.ergodicity.engine.service

import akka.actor.{Terminated, ActorSystem}
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import org.mockito.Mockito._
import com.ergodicity.engine.Services
import com.ergodicity.engine.underlying.ListenerFactory
import ru.micexrts.cgate
import cgate.{ISubscriber, Connection => CGConnection, Listener => CGListener, Publisher => CGPublisher}
import com.ergodicity.core.broker.Broker
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import com.ergodicity.engine.service.Service.Start
import com.ergodicity.cgate.{Active, Opening}
import com.ergodicity.engine.Services.Reporter

class TradingServiceSpec extends TestKit(ActorSystem("TradingServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val Id = Trading.Trading

  val listenerFactory = new ListenerFactory {
    def apply(connection: cgate.Connection, config: String, subscriber: ISubscriber) = mock(classOf[CGListener])
  }

  implicit val BrokerConfig = Broker.Config("000")

  "Trading Service" must {
    "start service" in {
      val publisher = mock(classOf[CGPublisher])
      val repliesConnection = mock(classOf[CGConnection])

      implicit val reporter = mock(classOf[Reporter])
      val broker = TestProbe()

      val service =  TestActorRef(new TradingService(listenerFactory, publisher, repliesConnection) {
        override val TradingBroker = broker.ref
      }, "TradingService")

      when("got Start message ")
      service ! Start

      then("should track Trading state")
      broker.expectMsg(SubscribeTransitionCallBack(service))

      when("Broker activated")
      service ! Transition(broker.ref, Opening, Active)

      then("Service Manager should be notified")
      verify(reporter).serviceStarted(Id)
    }

    "stop service" in {
      val publisher = mock(classOf[CGPublisher])
      val repliesConnection = mock(classOf[CGConnection])

      implicit val reporter = mock(classOf[Reporter])
      val broker = TestProbe()

      val service =  TestActorRef(new TradingService(listenerFactory, publisher, repliesConnection){
        override val TradingBroker = broker.ref
      }, "TradingService")

      when("stop Service")
      service ! Service.Stop

      then("service should be terminated")
      watch(service)
      expectMsg(Terminated(service))

      and("service manager should be notified")
      verify(reporter).serviceStopped(Id)
    }
  }
}