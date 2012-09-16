package com.ergodicity.core

import akka.pattern._
import akka.event.Logging
import akka.actor.ActorSystem
import java.util.concurrent.TimeUnit
import akka.dispatch.Await
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.cgate.DataStream
import org.scalatest.{BeforeAndAfter, GivenWhenThen, BeforeAndAfterAll, WordSpec}
import com.ergodicity.core.PositionsTracking._
import position.PositionActor.{PositionTransition, CurrentPosition, SubscribePositionUpdates}
import position.{PositionDynamics, Position}
import session.SessionActor.AssignedContents
import com.ergodicity.core.PositionsTracking.Positions
import com.ergodicity.core.PositionsTracking.TrackedPosition

class PositionsTrackingSpec extends TestKit(ActorSystem("PositionsTrackingSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll with BeforeAndAfter {
  val log = Logging(system, self)

  implicit val TimeOut = akka.util.Timeout(100, TimeUnit.MILLISECONDS)

  override def afterAll() {
    system.shutdown()
  }

  val isin = Isin("RTS-9.12")
  val isinId = IsinId(100)

  val assignedContents = AssignedContents(Set(FutureContract(isinId, isin, ShortIsin(""), "Future Contract")))

  "Positions Tracking" must {

    "handle positions updates" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      positions ! assignedContents
      positions ! PositionUpdated(isinId, Position(1), PositionDynamics(buys = 1))

      assert(underlying.positions.size == 1)

      val positionRef = underlying.positions(isin)
      positionRef ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(isin, Position(1)))
    }

    "close discarded positions" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      positions ! assignedContents
      when("position created")
      positions ! PositionUpdated(isinId, Position(1), PositionDynamics(buys = 1))

      then("should create actor for if")
      val positionRef = underlying.positions(isin)
      positionRef ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(isin, Position(1)))

      when("position discarded")
      positions ! PositionDiscarded(isinId)

      then("positions size should remain the same")
      assert(underlying.positions.size == 1)

      and("notified on position transition")
      expectMsg(PositionTransition(isin, Position(1), Position.flat))
    }

    "update existing positions" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      positions ! assignedContents
      positions ! PositionUpdated(isinId, Position(1), PositionDynamics(buys = 1))

      val positionRef = underlying.positions(isin)
      positionRef ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(isin, Position(1)))

      when("position updated")
      positions ! PositionUpdated(isinId, Position(2), PositionDynamics(buys = 2))

      then("should get transtition notification")
      expectMsg(PositionTransition(isin, Position(1), Position(2)))
    }

    "create new positions if it doesn't exists" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      val position = Await.result((positions ? GetPositionActor(isin)).mapTo[TrackedPosition], TimeOut.duration)

      assert(underlying.positions.size == 1)

      position.positionActor ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(isin, Position.flat))
    }

    "return existing position on track position event" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")


      positions ! assignedContents
      positions ! PositionUpdated(isinId, Position(1), PositionDynamics(buys = 1))

      val trackedPosition = Await.result((positions ? GetPositionActor(isin)).mapTo[TrackedPosition], TimeOut.duration)
      trackedPosition.positionActor ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(isin, Position(1)))
    }

    "get all opened positions" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")

      positions ! assignedContents
      positions ! PositionUpdated(isinId, Position(1), PositionDynamics(buys = 1))

      val openPositions = Await.result((positions ? GetPositions).mapTo[Positions], TimeOut.duration)
      assert(openPositions.positions.size == 1)
      assert(openPositions.positions(isin) == Position(1))
    }
  }

}