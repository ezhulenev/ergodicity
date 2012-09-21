package com.ergodicity.engine.service

import akka.actor.FSM.CurrentState
import akka.actor.FSM.Transition
import akka.actor.{Terminated, Actor, ActorSystem}
import akka.event.Logging
import akka.testkit._
import akka.util.duration._
import com.ergodicity.cgate.DataStreamState
import com.ergodicity.core._
import com.ergodicity.core.broker.MarketCommand
import com.ergodicity.core.broker.OrderId
import com.ergodicity.core.order.Order
import com.ergodicity.core.order.OrdersTracking.{GetOrder, OrderRef}
import com.ergodicity.engine.Services
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.engine.service.Trading.{Sell, OrderExecution, Buy}
import com.ergodicity.engine.underlying.ListenerFactory
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{ISubscriber, Connection => CGConnection, Listener => CGListener, Publisher => CGPublisher}

class TradingServiceSpec extends TestKit(ActorSystem("TradingServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val Id = Trading.Trading

  val listenerFactory = new ListenerFactory {
    def apply(connection: CGConnection, config: String, subscriber: ISubscriber) = mock(classOf[CGListener])
  }


  val publisherName = "Engine"
  val brokerCode = "000"

  "Trading Service" must {
    "initialized in Idle state" in {
      val publisher = mock(classOf[CGPublisher])
      val repliesConnection = mock(classOf[CGConnection])
      val replicationConnection = mock(classOf[CGConnection])

      implicit val services = mock(classOf[Services])
      Mockito.when(services.service(InstrumentData.InstrumentData)).thenReturn(system.deadLetters)

      val service = TestFSMRef(new TradingService(listenerFactory, publisherName, brokerCode, publisher, repliesConnection, replicationConnection), "TradingService")

      assert(service.stateName == TradingState.Idle)
    }

    "start service" in {
      val publisher = mock(classOf[CGPublisher])
      val repliesConnection = mock(classOf[CGConnection])
      val replicationConnection = mock(classOf[CGConnection])

      implicit val services = mock(classOf[Services])
      Mockito.when(services.service(InstrumentData.InstrumentData)).thenReturn(system.deadLetters)

      given("Trading service")
      val service = TestFSMRef(new TradingService(listenerFactory, publisherName, brokerCode, publisher, repliesConnection, replicationConnection), "TradingService")
      val underlying = service.underlyingActor.asInstanceOf[TradingService]

      when("receive Start message")
      service ! Start
      when("should go to Starting state")
      assert(service.stateName == TradingState.Starting)

      when("broker Active and both streams goes Online")
      service ! CurrentState(underlying.TradingBroker, com.ergodicity.cgate.Active)
      service ! CurrentState(underlying.FutOrdersStream, DataStreamState.Online)
      service ! CurrentState(underlying.OptOrdersStream, DataStreamState.Online)

      then("service shoud be started")
      assert(service.stateName == TradingState.Started)
      and("Service Manager should be notified")
      verify(services).serviceStarted(Trading.Trading)
    }

    "stop service" in {
      val publisher = mock(classOf[CGPublisher])
      val repliesConnection = mock(classOf[CGConnection])
      val replicationConnection = mock(classOf[CGConnection])

      implicit val services = mock(classOf[Services])
      Mockito.when(services.service(InstrumentData.InstrumentData)).thenReturn(system.deadLetters)

      given("Trading service in started state")
      val service = TestFSMRef(new TradingService(listenerFactory, publisherName, brokerCode, publisher, repliesConnection, replicationConnection), "TradingService")
      service.setState(TradingState.Started)
      val underlying = service.underlyingActor.asInstanceOf[TradingService]

      when("receive Stop message")
      service ! Stop
      when("should go to Stopping state")
      assert(service.stateName == TradingState.Stopping)

      when("broker Closed and both streams Closed too")
      service ! Transition(underlying.TradingBroker, com.ergodicity.cgate.Active, com.ergodicity.cgate.Closed)
      service ! Transition(underlying.FutOrdersStream, DataStreamState.Online, DataStreamState.Closed)
      service ! Transition(underlying.OptOrdersStream, DataStreamState.Online, DataStreamState.Closed)

      then("service shoud be stopped")
      watch(service)
      expectMsg(Terminated(service))
      and("Service Manager should be notified")
      verify(services).serviceStopped(Trading.Trading)
    }

    "buy & sell future" in {
      val publisher = mock(classOf[CGPublisher])
      val repliesConnection = mock(classOf[CGConnection])
      val replicationConnection = mock(classOf[CGConnection])

      implicit val services = mock(classOf[Services])
      Mockito.when(services.service(InstrumentData.InstrumentData)).thenReturn(system.deadLetters)

      val isinId = IsinId(100)
      val isin = Isin("Isin")
      val shortIsin = ShortIsin("Short")
      val orderId = 99999

      val broker = TestActorRef(new Actor {
        protected def receive = {
          case command: MarketCommand[_, _, _] => sender ! OrderId(orderId)
        }
      }, "Broker")

      val tracker = TestActorRef(new Actor {
        protected def receive = {
          case GetOrder(id) if (id == orderId) => sender ! OrderRef(mock(classOf[Order]), system.deadLetters)
        }
      }, "OrderTracker")

      given("Trading service in started state")
      val service = TestFSMRef(new TradingService(listenerFactory, publisherName, brokerCode, publisher, repliesConnection, replicationConnection) {
        override val TradingBroker = broker
        override val OrdersTracking = tracker
      }, "TradingService")
      service.setState(TradingState.Started)

      when("receive buy command")
      service ! Buy(FutureContract(isinId, isin, shortIsin, ""), 1, 100)
      then("should return OrderExecution")
      val buyReport = receiveOne(100.millis).asInstanceOf[OrderExecution]
      log.info("Buy Report = " + buyReport)

      when("receive Sell command")
      service ! Sell(FutureContract(isinId, isin, shortIsin, ""), 1, 100)
      then("should return OrderExecution")
      val sellReport = receiveOne(100.millis).asInstanceOf[OrderExecution]
      log.info("Sell Report = " + sellReport)
    }
  }
}