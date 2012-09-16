package com.ergodicity.engine.strategy

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit._
import com.ergodicity.cgate.DataStream
import com.ergodicity.cgate.SysEvent.SessionDataReady
import com.ergodicity.core.PositionsTracking.PositionUpdated
import com.ergodicity.core.SessionsTracking.FutSessContents
import com.ergodicity.core.SessionsTracking.FutSysEvent
import com.ergodicity.core.SessionsTracking.OptSysEvent
import com.ergodicity.core.SessionsTracking.SessionEvent
import com.ergodicity.core._
import com.ergodicity.core.order.OrderActor.{OrderEvent, SubscribeOrderEvents}
import com.ergodicity.core.order.{FillOrder, Order}
import com.ergodicity.core.position.{PositionDynamics, Position}
import session.InstrumentParameters.{FutureParameters, Limits}
import com.ergodicity.core.session.SessionActor.AssignedContents
import com.ergodicity.core.session._
import com.ergodicity.engine.service.Trading._
import com.ergodicity.engine.service.{InstrumentData, Portfolio, Trading}
import com.ergodicity.engine.strategy.Strategy.Start
import com.ergodicity.engine.{Services, StrategyEngine}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}

class CloseAllPositionsSpec extends TestKit(ActorSystem("CloseAllPositionsSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val id = CloseAllPositions.CloseAllPositions

  val sessionId = SessionId(100, 100)

  implicit val isin1 = Isin("RTS-9.12")
  implicit val isinId1 = IsinId(100)

  implicit val isin2 = Isin("RTS-12.12")
  implicit val isinId2 = IsinId(101)

  val futureContract1 = FutureContract(isinId1, isin1, ShortIsin(""), "Future Contract #1")
  val futureContract2 = FutureContract(isinId2, isin2, ShortIsin(""), "Future Contract #2")

  val parameters1 = FutureParameters(100, Limits(10, 20))
  val parameters2 = FutureParameters(1000, Limits(100, 200))

  val assignedParameters = AssignedContents(Set(futureContract1, futureContract2))

  private def portfolioService = {
    val portfolio = TestActorRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Portfolio")
    portfolio ! assignedParameters

    portfolio ! PositionUpdated(isinId1, Position(1), PositionDynamics(buys = 1))
    portfolio ! PositionUpdated(isinId2, Position(-3), PositionDynamics(buys = 5, sells = 8))

    portfolio
  }

  private def instrumentDataService = {
    val sessionsTracking = TestActorRef(new SessionsTracking(system.deadLetters, system.deadLetters), "Sessions")
    sessionsTracking ! FutSessContents(sessionId.fut, futureContract1, parameters1, InstrumentState.Online)
    sessionsTracking ! FutSessContents(sessionId.fut, futureContract2, parameters2, InstrumentState.Online)
    sessionsTracking ! SessionEvent(sessionId, mock(classOf[Session]), SessionState.Online, IntradayClearingState.Oncoming)
    sessionsTracking ! FutSysEvent(SessionDataReady(1, 100))
    sessionsTracking ! OptSysEvent(SessionDataReady(1, 100))
    sessionsTracking
  }

  "Close All Positions" must {
    "load all current positions" in {
      // Use PositionsTracking as Portfolio service
      val portfolio = portfolioService

      // Prepare mock for engine and services
      val engine = mock(classOf[StrategyEngine])
      val services = mock(classOf[Services])
      Mockito.when(engine.services).thenReturn(services)
      Mockito.when(services.apply(Portfolio.Portfolio)).thenReturn(portfolio)

      // Build strategy
      val strategy = TestActorRef(new CloseAllPositions(engine), "CloseAllPositions")
      val underlying = strategy.underlyingActor

      assert(underlying.positions.size == 2)
      assert(underlying.positions(isin1) == Position(1))
      assert(underlying.positions(isin2) == Position(-3))

      verify(engine).reportReady(Map[Isin, Position](isin1 -> Position(1), isin2 -> Position(-3)))(CloseAllPositions.CloseAllPositions)
    }

    "close positions" in {
      // TestProbes
      val trading = TestProbe()

      // Prepare mock for engine and services
      val engine = mock(classOf[StrategyEngine])
      val services = mock(classOf[Services])
      Mockito.when(engine.services).thenReturn(services)
      Mockito.when(services.apply(Trading.Trading)).thenReturn(trading.ref)
      Mockito.when(services.apply(Portfolio.Portfolio)).thenReturn(portfolioService)
      Mockito.when(services.service(InstrumentData.InstrumentData)).thenReturn(instrumentDataService)

      // Build strategy
      val strategy = TestFSMRef(new CloseAllPositions(engine), "CloseAllPositions")

      strategy ! Start
      assert(strategy.stateName == CloseAllPositionsState.ClosingPositions)

      // Expect buying messages
      trading.expectMsgAllOf(Sell(futureContract1, 1, 90), Buy(futureContract2, 3, 1200))

      when("get execution reports")
      val orderActor1 = TestProbe()
      val orderActor2 = TestProbe()
      val order1 = Order(1, sessionId.fut, isinId1, null, null, null, 1)
      val order2 = Order(2, sessionId.fut, isinId2, null, null, null, 3)
      strategy ! new OrderExecution(futureContract1, order1, orderActor1.ref)(system.deadLetters)
      strategy ! new OrderExecution(futureContract2, order2, orderActor2.ref)(system.deadLetters)
      then("should subscribe for order events")
      orderActor1.expectMsg(SubscribeOrderEvents(strategy))
      orderActor2.expectMsg(SubscribeOrderEvents(strategy))

      when("orders filled")
      strategy ! OrderEvent(order1, FillOrder(100, 1))
      strategy ! OrderEvent(order2, FillOrder(100, 3))
      then("should go to PositionsClosed state")
      assert(strategy.stateName == CloseAllPositionsState.PositionsClosed)
    }
  }
}