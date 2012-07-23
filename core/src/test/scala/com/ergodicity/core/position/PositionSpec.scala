package com.ergodicity.core.position

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.ActorSystem
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.core.common.FullIsin

class PositionSpec extends TestKit(ActorSystem("PositionSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val isin = FullIsin(166911, "GMKR-6.12", "GMM2")

  "Position" must {

    import PositionState._

    "initialied in closed state" in {
      val position = TestFSMRef(new Position(isin))
      assert(position.stateName == UndefinedPosition)
    }

    "go to OpenedPosition on update" in {
      val position = TestFSMRef(new Position(isin))
      position ! UpdatePosition(PositionData(0, 0, 0, 0, BigDecimal(0), 0))
      assert(position.stateName == OpenedPosition)
    }

    "termindate position" in {
      val position = TestFSMRef(new Position(isin))
      position ! UpdatePosition(PositionData(0, 0, 0, 0, BigDecimal(0), 0))
      position ! TerminatePosition
      assert(position.stateName == UndefinedPosition)
    }

    "handle position updates" in {
      val position = TestFSMRef(new Position(isin))
      position ! SubscribePositionUpdates(self)

      val data1 = PositionData(0, 0, 0, 0, BigDecimal(0), 0)
      position ! UpdatePosition(data1)
      expectMsg(PositionOpened)
      expectMsg(PositionUpdated(position, data1))

      val data2 = PositionData(1, 0, 0, 0, BigDecimal(0), 0)
      position ! UpdatePosition(data2)
      expectMsg(PositionUpdated(position, data2))

      position ! TerminatePosition
      expectMsg(PositionTerminated)
      assert(position.stateName == UndefinedPosition)
    }
  }
}
