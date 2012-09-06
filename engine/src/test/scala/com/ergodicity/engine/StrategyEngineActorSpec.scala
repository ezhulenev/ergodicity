package com.ergodicity.engine

import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import service.Portfolio
import strategy.{CloseAllPositions, StrategyId, StrategiesFactory}
import akka.testkit.{TestActorRef, TestFSMRef, ImplicitSender, TestKit}
import akka.actor._
import akka.event.Logging
import akka.util.duration._
import com.ergodicity.engine.StrategyEngine.{ReconciliationFailed, StrategyReady, PrepareStrategies}
import com.ergodicity.core._
import com.ergodicity.core.session.Instrument
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.Pos
import position.Position
import session.Instrument.Limits
import session.SessionActor.AssignedInstruments
import akka.actor.InvalidActorNameException
import com.ergodicity.core.FutureContract
import com.ergodicity.cgate.DataStream
import com.ergodicity.core.PositionsTrackingState.Online
import com.ergodicity.cgate.repository.Repository.Snapshot
import org.mockito.Mockito._
import org.mockito.Mockito
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.actor.SupervisorStrategy.Stop
import java.util.concurrent.{TimeUnit, CountDownLatch}

class StrategyEngineActorSpec extends TestKit(ActorSystem("StrategyEngineActorSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit case object TestStrategy extends StrategyId

  def testFactory(ref: ActorRef) = new StrategiesFactory {

    class TestStrategy extends Actor {
      override def preStart() {
        ref ! "Started"
      }

      protected def receive = null
    }

    def strategies = ((_: StrategyEngine) => Props(new TestStrategy())) :: Nil
  }

  implicit val services = mock(classOf[Services])

  "Strategy Engine" must {
    "prepare all strategies" in {
      given("Engine with one Strategy")
      val engine = TestFSMRef(new StrategyEngineActor(testFactory(self)), "StrategyEngine")
      expectNoMsg(300.millis)
      assert(engine.stateName == StrategyEngineState.Idle)

      when("engine receives StartStrategies message")
      engine ! PrepareStrategies

      then("it should start all strategies from factroy")
      expectMsg("Started")

      and("go to Preparing$ state")
      assert(engine.stateName == StrategyEngineState.Preparing)
    }

    "fail to start strategies with same name" in {
      given("Engine with one Strategy")
      val engine = TestFSMRef(new StrategyEngineActor(testFactory(system.deadLetters) & testFactory(system.deadLetters)), "StrategyEngine")

      when("engine receives StartStrategies message")
      intercept[InvalidActorNameException] {
        engine.receive(PrepareStrategies)
      }
    }

    "succesfully prepare strategies when positions reconciled" in {
      // Use PositionsTracking as Portfolio service
      val portfolio = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Portfolio")
      portfolio.setState(Online)
      portfolio ! assignedInstruments
      portfolio ! Snapshot(
        portfolio.underlyingActor.asInstanceOf[PositionsTracking].PositionsRepository,
        positionRecord(3, buys = 5, sells = 2)(isinId2) :: positionRecord(1, buys = 1)(isinId1) :: Nil
      )

      // Prepare mock for services
      implicit val services = mock(classOf[Services])
      Mockito.when(services.apply(Portfolio.Portfolio)).thenReturn(portfolio)

      val engine = TestFSMRef(new StrategyEngineActor(CloseAllPositions()), "StrategyEngine")

      // Prepare strategies
      engine ! SubscribeTransitionCallBack(self)
      engine ! PrepareStrategies

      // Receive all state transitions while engine not ready
      receiveWhile(1.second) {
        case CurrentState(_, _) =>
        case Transition(_, _, to: StrategyEngineState) if (to != StrategyEngineState.StrategiesReady) =>
      }
      expectMsg(Transition(engine, StrategyEngineState.Reconciling, StrategyEngineState.StrategiesReady))

      log.info("Engine state = " + engine.stateName)
    }

    "fail on reconciling duplicated positions" in {

      // Use PositionsTracking as Portfolio service
      val portfolio = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Portfolio")
      portfolio.setState(Online)
      portfolio ! assignedInstruments
      portfolio ! Snapshot(
        portfolio.underlyingActor.asInstanceOf[PositionsTracking].PositionsRepository,
        positionRecord(3, buys = 5, sells = 2)(isinId2) :: positionRecord(1, buys = 1)(isinId1) :: Nil
      )

      // Prepare mock for services
      implicit val services = mock(classOf[Services])
      Mockito.when(services.apply(Portfolio.Portfolio)).thenReturn(portfolio)

      val reconciliationFailed = new CountDownLatch(1)

      val guardian = TestActorRef(new Actor {
        val engine = context.actorOf(Props(new StrategyEngineActor(CloseAllPositions() & testFactory(system.deadLetters))), "StrategyEngine")
        override def supervisorStrategy() = OneForOneStrategy() {
          case r: ReconciliationFailed =>
            reconciliationFailed.countDown()
            Stop
        }
        protected def receive = null
      })
      val engine = guardian.underlyingActor.engine

      // Prepare strategies
      watch(engine)
      engine ! PrepareStrategies
      engine ! StrategyReady(TestStrategy, Map(isin1 -> Position(1), isin2 -> Position(1)))

      // Ensure that exception was thrown
      reconciliationFailed.await(1, TimeUnit.SECONDS)
      expectMsg(Terminated(engine))
    }
  }

  implicit val isin1: Isin = Isin("RTS-9.12")
  implicit val isinId1 = IsinId(100)

  implicit val isin2: Isin = Isin("RTS-12.12")
  implicit val isinId2 = IsinId(101)

  val assignedInstruments = AssignedInstruments(Set(
    Instrument(FutureContract(isinId1, isin1, ShortIsin(""), "Future Contract #1"), Limits(0, 0)),
    Instrument(FutureContract(isinId2, isin2, ShortIsin(""), "Future Contract #2"), Limits(0, 0))
  ))

  def positionRecord(pos: Int, open: Int = 0, buys: Int = 0, sells: Int = 0)(id: IsinId) = {
    val buff = ByteBuffer.allocate(1000)
    val position = new Pos.position(buff)
    position.set_isin_id(id.id)
    position.set_open_qty(open)
    position.set_buys_qty(buys)
    position.set_sells_qty(sells)
    position.set_pos(pos)
    position.set_net_volume_rur(new java.math.BigDecimal(100))
    position
  }
}