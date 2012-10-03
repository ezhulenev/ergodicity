package com.ergodicity.engine.strategy

import akka.actor.{Props, ActorRef, ActorSystem}
import akka.event.Logging
import akka.testkit.TestActor.AutoPilot
import akka.testkit._
import com.ergodicity.cgate.DataStream
import com.ergodicity.cgate.SysEvent.SessionDataReady
import com.ergodicity.core.PositionsTracking.PositionUpdated
import com.ergodicity.core.SessionsTracking.FutSessContents
import com.ergodicity.core.SessionsTracking.FutSysEvent
import com.ergodicity.core.SessionsTracking.OptSysEvent
import com.ergodicity.core.SessionsTracking.SessionEvent
import com.ergodicity.core._
import com.ergodicity.core.order.{Fill, Order}
import com.ergodicity.core.order.OrderActor.{OrderEvent, SubscribeOrderEvents}
import com.ergodicity.core.position.{PositionDynamics, Position}
import com.ergodicity.core.session.SessionActor.AssignedContents
import com.ergodicity.core.session._
import com.ergodicity.engine.service.Trading._
import com.ergodicity.engine.service.{InstrumentData, Portfolio, Trading}
import com.ergodicity.engine.strategy.Strategy.Start
import com.ergodicity.engine.{Services, StrategyEngine}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import session.InstrumentParameters.{FutureParameters, Limits}
import com.ergodicity.engine.strategy.CoverPositions.CoverAll

class CoverAllPositionsSpec extends TestKit(ActorSystem("CoverAllPositionsSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val id = CoverAllPositions.CoverAllPositions

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
    val portfolio = system.actorOf(Props(new PositionsTracking(TestFSMRef(new DataStream, "DataStream"))))
    portfolio ! assignedParameters

    portfolio ! PositionUpdated(isinId1, Position(1), PositionDynamics(buys = 1))
    portfolio ! PositionUpdated(isinId2, Position(-3), PositionDynamics(buys = 5, sells = 8))

    portfolio
  }

  private def instrumentDataService = {
    val sessionsTracking = system.actorOf(Props(new SessionsTracking(system.deadLetters, system.deadLetters)))
    sessionsTracking ! FutSessContents(sessionId.fut, futureContract1, parameters1, InstrumentState.Online)
    sessionsTracking ! FutSessContents(sessionId.fut, futureContract2, parameters2, InstrumentState.Online)
    sessionsTracking ! SessionEvent(sessionId, mock(classOf[Session]), SessionState.Online, IntradayClearingState.Oncoming)
    sessionsTracking ! FutSysEvent(SessionDataReady(1, 100))
    sessionsTracking ! OptSysEvent(SessionDataReady(1, 100))
    sessionsTracking
  }

  "Close All Positions" must {
    "load all current positions" in {
      // Prepare mock for engine and services
      implicit val engine = mock(classOf[StrategyEngine])
      val services = mock(classOf[Services])
      Mockito.when(engine.services).thenReturn(services)
      Mockito.when(services.apply(Trading.Trading)).thenReturn(system.deadLetters)
      Mockito.when(services.apply(Portfolio.Portfolio)).thenReturn(portfolioService)
      Mockito.when(services.service(InstrumentData.InstrumentData)).thenReturn(instrumentDataService)

      // Build strategy
      val strategy = TestActorRef(new CoverPositions with CoverAll, "CoverPositions")
      val underlying = strategy.underlyingActor

      assert(underlying.positions.size == 2)
      assert(underlying.positions(futureContract1) == Position(1))
      assert(underlying.positions(futureContract2) == Position(-3))

      verify(engine).reportReady(Map[Security, Position](futureContract1 -> Position(1), futureContract2 -> Position(-3)))(CoverAllPositions.CoverAllPositions)

      strategy.stop()
    }

    "close positions" in {
      // TestProbes
      val trading = TestProbe()

      val orderActor1 = TestProbe()
      val orderActor2 = TestProbe()
      val order1 = Order(1, sessionId.fut, isinId1, null, null, 1, 1)
      val order2 = Order(2, sessionId.fut, isinId2, null, null, 3, 1)

      trading.setAutoPilot(new AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case order if (order == Sell(futureContract1, 1, 90)) =>
            sender ! new OrderExecution(futureContract1, order1, orderActor1.ref)(system.deadLetters)
            Some(this)

          case order if (order == Buy(futureContract2, 3, 1200)) =>
            sender ! new OrderExecution(futureContract2, order2, orderActor2.ref)(system.deadLetters)
            Some(this)
        }
      })

      // Prepare mock for engine and services
      implicit val engine = mock(classOf[StrategyEngine])
      val services = mock(classOf[Services])
      Mockito.when(engine.services).thenReturn(services)
      Mockito.when(services.apply(Trading.Trading)).thenReturn(trading.ref)
      Mockito.when(services.apply(Portfolio.Portfolio)).thenReturn(portfolioService)
      Mockito.when(services.service(InstrumentData.InstrumentData)).thenReturn(instrumentDataService)

      // Build strategy
      val strategy = TestFSMRef(new CoverPositions with CoverAll, "CoverPositions")

      strategy ! Start
      assert(strategy.stateName == CoverPositionsState.CoveringPositions)

      // Expect buying messages
      trading.expectMsgAllOf(Sell(futureContract1, 1, 90), Buy(futureContract2, 3, 1200))

      when("get execution reports")
      // ExecutionContext reports set up in AutoPilot upper
      then("should subscribe for order events")
      orderActor1.expectMsg(SubscribeOrderEvents(strategy))
      orderActor2.expectMsg(SubscribeOrderEvents(strategy))

      when("orders filled")
      strategy ! OrderEvent(order1, Fill(1, 0, None))
      strategy ! OrderEvent(order2, Fill(3, 0, None))
      then("should go to PositionsCovered state")
      assert(strategy.stateName == CoverPositionsState.PositionsCovered)

      strategy.stop()
    }
  }
}