package com.ergodicity.core.position

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.ActorSystem
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.core.Isins

class PositionActorSpec extends TestKit(ActorSystem("PositionActorSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val isin = Isins(166911, "GMKR-6.12", "GMM2")

  "PositionActor" must {

    import PositionActor._

    "initialied in Flat state" in {
      val position = TestActorRef(new PositionActor(isin))
      assert(position.underlyingActor.position == Flat)
    }

    "stay in Flat position on update with position = 0" in {
      val position = TestActorRef(new PositionActor(isin))
      position ! UpdatePosition(Flat, PositionDynamics())
      assert(position.underlyingActor.position == Flat)
    }

    "go to Long position on update" in {
      val position = TestActorRef(new PositionActor(isin))
      position ! UpdatePosition(Long(10), PositionDynamics(buys = 10))
      assert(position.underlyingActor.position == Long(10))
    }

    "handle position updates" in {
      val position = TestActorRef(new PositionActor(isin))
      position ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(position, Flat))

      val data1 = Long(10)
      position ! UpdatePosition(data1, PositionDynamics(buys = 10))
      assert(position.underlyingActor.position == data1)
      expectMsg(PositionTransition(position, Flat, data1))

      val data2 = Short(2)
      position ! UpdatePosition(data2, PositionDynamics(open = Flat, buys = 10, sells = 12))
      assert(position.underlyingActor.position == data2)
      expectMsg(PositionTransition(position, data1, data2))

      val data3 = Flat
      position ! UpdatePosition(Flat, PositionDynamics(open = Flat, buys = 12, sells = 12))
      assert(position.underlyingActor.position == Flat)
      expectMsg(PositionTransition(position, data2, data3))
    }
  }
}
