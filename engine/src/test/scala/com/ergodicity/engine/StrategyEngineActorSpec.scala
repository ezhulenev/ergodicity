package com.ergodicity.engine

import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import strategy.{StrategyId, StrategiesFactory}
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import akka.actor._
import akka.event.Logging
import akka.util.duration._
import com.ergodicity.engine.StrategyEngine.StartStrategies
import org.mockito.Mockito._

class StrategyEngineActorSpec extends TestKit(ActorSystem("StrategyEngineActorSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  def testFactory(ref: ActorRef) = new StrategiesFactory {

    implicit case object TestStrategy extends StrategyId

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
    "start all strategies" in {
      given("Engine with one Strategy")
      val engine = TestFSMRef(new StrategyEngineActor(testFactory(self)), "StrategyEngine")
      expectNoMsg(300.millis)
      assert(engine.stateName == StrategyEngineState.Idle)

      when("engine receives StartStrategies message")
      engine ! StartStrategies

      then("it should start all strategies from factroy")
      expectMsg("Started")

      and("go to Starting$ state")
      assert(engine.stateName == StrategyEngineState.Starting)
    }

    "fail to start strategies with same name" in {
      given("Engine with one Strategy")
      val engine = TestFSMRef(new StrategyEngineActor(testFactory(self) & testFactory(self)), "StrategyEngine")

      when("engine receives StartStrategies message")
      intercept[InvalidActorNameException] {
        engine.receive(StartStrategies)
      }
    }
  }

}