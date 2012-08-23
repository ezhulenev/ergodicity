package com.ergodicity.engine.strategy

import akka.actor.{Terminated, ActorRef, Props, ActorSystem}
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import com.ergodicity.engine.Engine
import com.ergodicity.engine.service.Positions
import com.ergodicity.core.PositionsTracking.{OpenPositions, GetOpenPositions}
import akka.testkit.TestActor.AutoPilot

class CloseAllPositionsManagerSpec extends TestKit(ActorSystem("CloseAllPositionsManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  private def mockEngine(positions: TestProbe) = TestActorRef(new Engine with Positions {

    def ServiceManager = system.deadLetters

    def StrategyManager = system.deadLetters

    def PosStream = system.deadLetters

    def Positions = positions.ref
  })

  "CloseAllPositions Manager" must {
    "get opened positions on start" in {
      val positions = TestProbe()

      positions.setAutoPilot(new AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case GetOpenPositions => sender ! OpenPositions(Nil); Some(this)
        }
      })

      val engine = mockEngine(positions).underlyingActor
      val manager: ActorRef = TestActorRef(Props(new CloseAllPositionsManager(engine)), "CloseAllPositionsManager")

      when("got Start message")
      manager ! Strategy.Start

      then("should log opened positions")
      positions.expectMsg(GetOpenPositions)
    }

    "stop actor on Strategy.Stop received" in {
      val positions = TestProbe()

      positions.setAutoPilot(new AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case GetOpenPositions => sender ! OpenPositions(Nil); Some(this)
        }
      })

      val engine = mockEngine(positions).underlyingActor
      val manager: ActorRef = TestActorRef(Props(new CloseAllPositionsManager(engine)), "CloseAllPositionsManager")

      watch(manager)

      when("stop Service")
      manager ! Strategy.Stop

      and("CloseAllPositions manager actor terminated")
      expectMsg(Terminated(manager))
    }
  }
}