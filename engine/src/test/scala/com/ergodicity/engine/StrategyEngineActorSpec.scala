package com.ergodicity.engine

import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.event.Logging
import akka.testkit._
import akka.util.duration._
import com.ergodicity.cgate.DataStream
import com.ergodicity.core.PositionsTracking.PositionUpdated
import com.ergodicity.core._
import com.ergodicity.core.position.{PositionDynamics, Position}
import com.ergodicity.core.session.SessionActor.AssignedContents
import com.ergodicity.engine.StrategyEngine._
import com.ergodicity.engine.strategy._
import java.util.concurrent.{TimeUnit, CountDownLatch}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import service.Portfolio

class StrategyEngineActorSpec extends TestKit(ActorSystem("StrategyEngineActorSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val isin1: Isin = Isin("RTS-9.12")
  implicit val isinId1 = IsinId(100)
  val futureContract1 = FutureContract(isinId1, isin1, ShortIsin(""), "Future Contract #1")

  implicit val isin2: Isin = Isin("RTS-12.12")
  implicit val isinId2 = IsinId(101)
  val futureContract2 = FutureContract(isinId2, isin2, ShortIsin(""), "Future Contract #2")

  val assignedContents = AssignedContents(Set(futureContract1, futureContract2))

  implicit case object TestStrategy extends StrategyId

  def testFactory(ref: ActorRef, pipeTo: ActorRef = system.deadLetters) = new SingleStrategyFactory {
    class TestStrategy extends Actor {
      override def preStart() {
        ref ! "Loaded"
      }

      protected def receive = {
        case event => pipeTo.tell(event, sender)
      }
    }

    def strategy = new StrategyBuilder(TestStrategy) {
      def props(implicit engine: StrategyEngine) = Props(new TestStrategy)
    }
  }

  implicit val services = mock(classOf[Services])

  "Strategy Engine" must {
    "load all strategies" in {
      given("Engine with one Strategy")
      val engine = TestFSMRef(new StrategyEngineActor(testFactory(self)), "StrategyEngine")
      expectNoMsg(300.millis)
      assert(engine.stateName == StrategyEngineState.Idle)

      when("engine receives StartStrategies message")
      engine ! LoadStrategies

      then("it should load all strategies from factroy")
      expectMsg("Loaded")

      and("go to Loading state")
      assert(engine.stateName == StrategyEngineState.Loading)
    }

    "fail to start strategies with same name" in {
      given("Engine with one Strategy")
      val engine = TestFSMRef(new StrategyEngineActor(testFactory(system.deadLetters) & testFactory(system.deadLetters)), "StrategyEngine")

      when("engine receives StartStrategies message")
      intercept[InvalidActorNameException] {
        engine.receive(LoadStrategies)
      }
    }

    "succesfully load strategies when positions reconciled" in {
      // Use PositionsTracking as Portfolio service
      val portfolio = TestActorRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Portfolio")
      portfolio ! assignedContents

      portfolio ! PositionUpdated(isinId1, Position(1), PositionDynamics(buys = 1))
      portfolio ! PositionUpdated(isinId2, Position(3), PositionDynamics(buys = 5, sells = 2))

      // Prepare mock for services
      implicit val services = mock(classOf[Services])
      Mockito.when(services.apply(Portfolio.Portfolio)).thenReturn(portfolio)

      val engine = TestFSMRef(new StrategyEngineActor(CoverAllPositions()), "StrategyEngine")

      // Prepare strategies
      engine ! SubscribeTransitionCallBack(self)
      engine ! LoadStrategies

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
      val portfolio = TestActorRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Portfolio")
      portfolio ! assignedContents

      portfolio ! PositionUpdated(isinId1, Position(1), PositionDynamics(buys = 1))
      portfolio ! PositionUpdated(isinId2, Position(3), PositionDynamics(buys = 5, sells = 2))

      // Prepare mock for services
      implicit val services = mock(classOf[Services])
      Mockito.when(services.apply(Portfolio.Portfolio)).thenReturn(portfolio)

      val reconciliationFailed = new CountDownLatch(1)

      val guardian = TestActorRef(new Actor {
        val engine = context.actorOf(Props(new StrategyEngineActor(CoverAllPositions() & testFactory(system.deadLetters))), "StrategyEngine")
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
      engine ! LoadStrategies
      engine ! StrategyReady(TestStrategy, Map(futureContract1 -> Position(1), futureContract2 -> Position(1)))

      // Ensure that exception was thrown
      reconciliationFailed.await(1, TimeUnit.SECONDS)
      expectMsg(Terminated(engine))
    }

    "support engine lifecycle" in {
      val probe = TestProbe()

      given("Engine with one Strategy")
      val engine = TestFSMRef(new StrategyEngineActor(testFactory(self, probe.ref)), "StrategyEngine")
      assert(engine.stateName == StrategyEngineState.Idle)

      when("engine receives StartStrategies message")
      engine ! LoadStrategies

      then("it should load all strategies from factroy")
      expectMsg("Loaded")

      and("go to Loading state")
      assert(engine.stateName == StrategyEngineState.Loading)

      // Skip reconciliation as it already tested

      given("engine switched to StrategiesReady state")
      engine.setState(StrategyEngineState.StrategiesReady)

      when("receive StartStrategies message")
      engine ! StartStrategies

      then("should start all child strategies")
      probe.expectMsg(Strategy.Start)

      and("go to Starting state")
      assert(engine.stateName == StrategyEngineState.Starting)

      when("strategy Started")
      engine ! StrategyStarted(TestStrategy)

      then("should go to Active state")
      assert(engine.stateName == StrategyEngineState.Active)

      when("receive StopStrategies message")
      engine ! StopStrategies

      then("should stop all child strategies")
      probe.expectMsg(Strategy.Stop)

      and("go to Stopping state")
      assert(engine.stateName == StrategyEngineState.Stopping)

      when("strategy Stopped")
      watch(engine)
      engine ! StrategyStopped(TestStrategy, Map())

      then("engine should be terminated")
      expectMsg(Terminated(engine))
    }
  }
}