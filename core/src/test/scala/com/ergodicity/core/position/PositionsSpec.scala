package com.ergodicity.core.position

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.ActorSystem
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.plaza2.scheme.Pos.PositionRecord
import com.ergodicity.plaza2.Repository.Snapshot
import com.ergodicity.core.common.IsinId
import akka.actor.FSM.{SubscribeTransitionCallBack, CurrentState}

class PositionsSpec extends TestKit(ActorSystem("PositionsSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val isin = 101
  val position = PositionRecord(0, 0, 0, isin, "", 0, 1, 0, 1, BigDecimal(100), 0, BigDecimal(0))

  "Positions" must {
    "handle first repository snapshot" in {
      val positions = TestActorRef(new Positions(), "Positions")

      val underlying = positions.underlyingActor
      val snapshot = Snapshot(underlying.positionsRepository, position :: Nil)
      positions ! snapshot

      assert(underlying.positions.size == 1)

      val positionRef = underlying.positions(IsinId(isin))
      positionRef ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(positionRef, OpenedPosition))
    }
  }

}