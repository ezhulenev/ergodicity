package com.ergodicity.core.position

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.ActorSystem
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.core.Isins

class PositionSpec extends TestKit(ActorSystem("PositionSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val isin = Isins(166911, "GMKR-6.12", "GMM2")

  "Position" must {

    import Position._
    import PositionState._

    "initialied in closed state" in {
      val position = TestFSMRef(new Position(isin))
      assert(position.stateName == ClosedPosition)
    }

    "stay in ClosedPosition on update with position = 0" in {
      val position = TestFSMRef(new Position(isin))
      position ! UpdatePosition(Data(position = 0))
      assert(position.stateName == ClosedPosition)
    }

    "go to OpenedPosition on update with position > 0" in {
      val position = TestFSMRef(new Position(isin))
      position ! UpdatePosition(Data(position = 10))
      assert(position.stateName == OpenedPosition)
    }

    "handle position updates" in {
      val position = TestFSMRef(new Position(isin))
      position ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(position, Data()))

      val data1 = Data(position = 1)
      position ! UpdatePosition(data1)
      assert(position.stateName == OpenedPosition)
      expectMsg(PositionUpdated(position, Data(), data1))

      val data2 = Data(position = 2)
      position ! UpdatePosition(data2)
      expectMsg(PositionUpdated(position, data1, data2))

      val data3 = Data(position = 0)
      position ! UpdatePosition(data3)
      assert(position.stateName == ClosedPosition)
      expectMsg(PositionUpdated(position, data2, data3))
    }
  }
}
