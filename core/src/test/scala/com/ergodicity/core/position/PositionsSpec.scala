package com.ergodicity.core.position

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.pattern._
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.plaza2.scheme.Pos.PositionRecord
import com.ergodicity.plaza2.Repository.Snapshot
import com.ergodicity.core.common.IsinId
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack, CurrentState}
import akka.actor.{ActorRef, ActorSystem}
import java.util.concurrent.TimeUnit
import akka.dispatch.Await

class PositionsSpec extends TestKit(ActorSystem("PositionsSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  implicit val TimeOut = akka.util.Timeout(100, TimeUnit.MILLISECONDS)

  override def afterAll() {
    system.shutdown()
  }

  val isin = 101
  val position = PositionRecord(0, 0, 0, isin, "", 0, 1, 0, 1, BigDecimal(100), 0, BigDecimal(0))
  val updatedPosition = PositionRecord(0, 0, 0, isin, "", 0, 2, 0, 2, BigDecimal(200), 0, BigDecimal(0))

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

    "terminate outdated positions" in {
      val positions = TestActorRef(new Positions(), "Positions")

      val underlying = positions.underlyingActor
      positions ! Snapshot(underlying.positionsRepository, position :: Nil)

      assert(underlying.positions.size == 1)

      val positionRef = underlying.positions(IsinId(isin))
      positionRef ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(positionRef, OpenedPosition))

      positions ! Snapshot(underlying.positionsRepository, List[PositionRecord]())

      assert(underlying.positions.size == 1)
      expectMsg(Transition(positionRef, OpenedPosition, UndefinedPosition))
    }

    "update existing positions" in {
      val positions = TestActorRef(new Positions(), "Positions")

      val underlying = positions.underlyingActor
      positions ! Snapshot(underlying.positionsRepository, position :: Nil)

      assert(underlying.positions.size == 1)

      val positionRef = underlying.positions(IsinId(isin))
      positionRef ! SubscribePositionUpdates(self)

      positions ! Snapshot(underlying.positionsRepository, updatedPosition :: Nil)
      expectMsg(PositionUpdated(positionRef, PositionData(0, 2, 0, 2, BigDecimal(200), 0)))
    }

    "create new positions if it doesn't exists" in {
      val positions = TestActorRef(new Positions(), "Positions")

      val underlying = positions.underlyingActor
      val position = Await.result((positions ? TrackPosition(IsinId(isin))).mapTo[ActorRef], TimeOut.duration)

      assert(underlying.positions.size == 1)

      position ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(position, UndefinedPosition))
    }

    "return existing position on track position event" in {
      val positions = TestActorRef(new Positions(), "Positions")

      val underlying = positions.underlyingActor
      positions ! Snapshot(underlying.positionsRepository, position :: Nil)

      val positionRef = Await.result((positions ? TrackPosition(IsinId(isin))).mapTo[ActorRef], TimeOut.duration)
      positionRef ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(positionRef, OpenedPosition))
    }
  }

}