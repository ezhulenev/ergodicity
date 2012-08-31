package com.ergodicity.engine

import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import strategy.{StrategyId, StrategiesFactory}
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import akka.actor._
import akka.event.Logging
import akka.util.duration._
import com.ergodicity.engine.StrategyEngine.{StartStrategies, EngineConfig}
import com.ergodicity.engine.StrategyEngineState.Starting

class StrategyEngineSpec extends TestKit(ActorSystem("StrategyEngineSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  def testFactory(ref: ActorRef) = new StrategiesFactory {

    implicit case object TestStrategy extends StrategyId

    def strategies(implicit config: EngineConfig) = Props(new Actor {
      override def preStart() {
        ref ! "Started"
      }

      protected def receive = null
    }) :: Nil
  }


  "Strategy Engine" must {
    "start all strategies" in {
      given("Engine with one Strategy")
      val engine = TestFSMRef(new StrategyEngine(testFactory(self)), "StrategyEngine")
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
      val engine = TestFSMRef(new StrategyEngine(testFactory(self) & testFactory(self)), "StrategyEngine")

      when("engine receives StartStrategies message")
      intercept[InvalidActorNameException] {
        engine.receive(StartStrategies)
      }
    }
  }

}