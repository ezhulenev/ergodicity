package com.ergodicity.engine.strategy

import akka.actor.{ActorRef, Actor, Props, ActorSystem}
import akka.event.Logging
import akka.testkit._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import org.joda.time.DateTime
import com.ergodicity.core._
import order.{Cancel, Fill, Order}
import order.OrderActor.OrderEvent
import position.Position
import session.InstrumentParameters.FutureParameters
import session.InstrumentParameters.Limits
import session.{IntradayClearingState, SessionState, Session, InstrumentState}
import session.SessionActor.AssignedContents
import com.ergodicity.core.SessionsTracking._
import org.mockito.Mockito._
import com.ergodicity.core.SessionsTracking.FutSysEvent
import com.ergodicity.cgate.SysEvent.SessionDataReady
import com.ergodicity.core.SessionsTracking.FutSessContents
import com.ergodicity.core.SessionId
import com.ergodicity.core.SessionsTracking.SessionEvent
import com.ergodicity.core.FutureContract
import com.ergodicity.engine.{Services, StrategyEngine}
import org.mockito.Mockito
import com.ergodicity.engine.service.InstrumentData
import com.ergodicity.engine.strategy.PositionManagement.{AcquirePosition, PositionBalanced, PositionManagerStarted}
import com.ergodicity.engine.service.Trading.{OrderExecution, Buy}
import akka.testkit.TestActor.AutoPilot
import com.ergodicity.engine.strategy.PositionManagerData.ManagedPosition

class PositionManagerSpec extends TestKit(ActorSystem("PositionManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val sessionId = SessionId(100, 100)

  implicit val isin1 = Isin("RTS-9.12")
  implicit val isinId1 = IsinId(100)

  implicit val isin2 = Isin("RTS-12.12")
  implicit val isinId2 = IsinId(101)

  val futureContract1 = FutureContract(isinId1, isin1, ShortIsin(""), "Future Contract #1")
  val futureContract2 = FutureContract(isinId2, isin2, ShortIsin(""), "Future Contract #2")

  val parameters1 = FutureParameters(100, Limits(10, 20))
  val parameters2 = FutureParameters(1000, Limits(100, 200))

  val assignedContents = AssignedContents(Set(futureContract1, futureContract2))

  private def instrumentDataService = {
    val sessionsTracking = TestActorRef(Props(new SessionsTracking(system.deadLetters, system.deadLetters)), "Sessions")
    sessionsTracking ! FutSessContents(sessionId.fut, futureContract1, parameters1, InstrumentState.Online)
    sessionsTracking ! FutSessContents(sessionId.fut, futureContract2, parameters2, InstrumentState.Online)
    sessionsTracking ! SessionEvent(sessionId, mock(classOf[Session]), SessionState.Online, IntradayClearingState.Oncoming)
    sessionsTracking ! FutSysEvent(SessionDataReady(1, 100))
    sessionsTracking ! OptSysEvent(SessionDataReady(1, 100))
    sessionsTracking
  }

  val SystemTrade = false
  val start = new DateTime(2012, 1, 1, 12, 0)

  class DummyStrategy(val engine: StrategyEngine) extends Actor with Strategy with InstrumentWatcher {
    protected def receive = null
  }

  private def buildPositionManager(trading: ActorRef = system.deadLetters) = {
    // Prepare mock for engine and services
    implicit val engine = mock(classOf[StrategyEngine])
    val services = mock(classOf[Services])
    Mockito.when(engine.services).thenReturn(services)
    Mockito.when(services.service(InstrumentData.InstrumentData)).thenReturn(instrumentDataService)

    // Build manager
    implicit val config = PositionManagementConfig(self)
    implicit val watcher = TestActorRef(new DummyStrategy(engine), "Strategy").underlyingActor.asInstanceOf[InstrumentWatcher]

    TestFSMRef(new PositionManagerActor(trading, isin1, Position.flat), "PositionManager")
  }

  "PositionManager" must {
    "wait for position manager catch instrument and goes to balanced state" in {
      val positionManager = buildPositionManager()

      awaitCond(positionManager.stateName == PositionManagerState.Balanced)

      expectMsg(PositionManagerStarted(isin1, futureContract1, Position.flat))
      expectMsg(PositionBalanced(isin1, Position.flat))
    }

    "balance acquired position from Balanced state" in {
      val order = Order(1, 1, isinId1, OrderDirection.Buy, 120, 10, 2)
      val execution = spy(new OrderExecution(futureContract1, order, system.deadLetters)(system.deadLetters))

      val trading = TestProbe()
      trading.setAutoPilot(new AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case buy: Buy =>
            sender ! execution
            None
          case _ => None
        }
      })

      // Build position manager
      val positionManager = buildPositionManager(trading.ref)

      awaitCond(positionManager.stateName == PositionManagerState.Balanced)
      expectMsg(PositionManagerStarted(isin1, futureContract1, Position.flat))
      expectMsg(PositionBalanced(isin1, Position.flat))

      when("acquire position")
      positionManager ! AcquirePosition(Position(10))

      then("should but required contracts")
      trading.expectMsg(Buy(futureContract1, 10, parameters1.lastClQuote + parameters1.limits.upper))
      and("go to Balancing state")
      awaitCond(positionManager.stateName == PositionManagerState.Balancing)

      when("order filled")
      positionManager ! OrderEvent(order, Fill(10, 0, None))
      then("should go to Balanced state")
      expectMsg(PositionBalanced(isin1, Position(10)))
      awaitCond(positionManager.stateName == PositionManagerState.Balanced)

      awaitCond(positionManager.underlyingActor.asInstanceOf[PositionManagerActor].orders.size == 1)
      when("order cancelled")
      positionManager ! OrderEvent(order, Cancel(0))
      then("should remove it from internal storage")
      awaitCond(positionManager.underlyingActor.asInstanceOf[PositionManagerActor].orders.size == 0)
    }

    "balance acquired position from Balancing state" in {
      val order = Order(1, 1, isinId1, OrderDirection.Buy, 120, 10, 2)
      val execution = spy(new OrderExecution(futureContract1, order, system.deadLetters)(system.deadLetters))

      val trading = TestProbe()
      trading.setAutoPilot(new AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case buy: Buy =>
            sender ! execution
            None
          case _ => None
        }
      })

      // Build position manager
      val positionManager = buildPositionManager(trading.ref)

      awaitCond(positionManager.stateName == PositionManagerState.Balanced)
      expectMsg(PositionManagerStarted(isin1, futureContract1, Position.flat))
      expectMsg(PositionBalanced(isin1, Position.flat))

      // Update state
      given("PositionManager in Balancing state")
      positionManager.setState(PositionManagerState.Balancing, positionManager.stateData.asInstanceOf[ManagedPosition].copy(target = Position(5)))

      when("acquire position")
      positionManager ! AcquirePosition(Position(15))

      then("should but required contracts")
      trading.expectMsg(Buy(futureContract1, 10, parameters1.lastClQuote + parameters1.limits.upper))
      and("go to Balancing state")
      awaitCond(positionManager.stateName == PositionManagerState.Balancing)

      when("order filled")
      positionManager ! OrderEvent(order, Fill(10, 0, None))

      then("stay in balancing state")
      awaitCond(positionManager.stateName == PositionManagerState.Balancing)
      awaitCond(positionManager.stateData.asInstanceOf[ManagedPosition].actual == Position(10))
      awaitCond(positionManager.stateData.asInstanceOf[ManagedPosition].target == Position(15))
    }

  }
}