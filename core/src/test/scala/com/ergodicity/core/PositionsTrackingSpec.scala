package com.ergodicity.core

import akka.actor.ActorSystem
import akka.dispatch.Await
import akka.event.Logging
import akka.pattern._
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.cgate.DataStream
import com.ergodicity.core.PositionsTracking._
import java.util.concurrent.TimeUnit
import org.scalatest.{BeforeAndAfter, GivenWhenThen, BeforeAndAfterAll, WordSpec}
import position.PositionActor.{PositionTransition, CurrentPosition, SubscribePositionUpdates}
import position.{PositionDynamics, Position}
import session.SessionActor.AssignedContents

class PositionsTrackingSpec extends TestKit(ActorSystem("PositionsTrackingSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll with BeforeAndAfter {
  val log = Logging(system, self)

  implicit val TimeOut = akka.util.Timeout(200, TimeUnit.MILLISECONDS)

  override def afterAll() {
    system.shutdown()
  }

  val futureContract = FutureContract(IsinId(100), Isin("RTS-9.12"), ShortIsin("RIU2"), "Future contract")

  val assignedContents = AssignedContents(Set(futureContract))

  "Positions Tracking" must {

    "handle positions updates" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      positions ! assignedContents
      positions ! PositionUpdated(futureContract.id, Position(1), PositionDynamics(buys = 1))

      assert(underlying.positions.size == 1)

      val positionRef = underlying.positions(futureContract)
      positionRef ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(futureContract, Position(1), PositionDynamics(buys = 1)))
    }

    "close discarded positions" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      positions ! assignedContents
      when("position created")
      positions ! PositionUpdated(futureContract.id, Position(1), PositionDynamics(buys = 1))

      then("should create actor for if")
      val positionRef = underlying.positions(futureContract)
      positionRef ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(futureContract, Position(1), PositionDynamics(buys = 1)))

      when("position discarded")
      positions ! PositionDiscarded(futureContract.id)

      then("positions size should remain the same")
      assert(underlying.positions.size == 1)

      and("notified on position transition")
      expectMsg(PositionTransition(futureContract, (Position(1), PositionDynamics(buys = 1)), (Position.flat, PositionDynamics.empty)))
    }

    "update existing positions" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      positions ! assignedContents
      positions ! PositionUpdated(futureContract.id, Position(1), PositionDynamics(buys = 1))

      val positionRef = underlying.positions(futureContract)
      positionRef ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(futureContract, Position(1), PositionDynamics(buys = 1)))

      when("position updated")
      positions ! PositionUpdated(futureContract.id, Position(2), PositionDynamics(buys = 2))

      then("should get transtition notification")
      expectMsg(PositionTransition(futureContract, (Position(1), PositionDynamics(buys = 1)), (Position(2), PositionDynamics(buys = 2))))
    }

    "create new positions if it doesn't exists" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      val position = Await.result((positions ? GetTrackedPosition(futureContract)).mapTo[TrackedPosition], TimeOut.duration)

      assert(underlying.positions.size == 1)

      position.positionActor ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(futureContract, Position.flat, PositionDynamics.empty))
    }

    "return existing position on track position event" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")


      positions ! assignedContents
      positions ! PositionUpdated(futureContract.id, Position(1), PositionDynamics(buys = 1))

      val trackedPosition = Await.result((positions ? GetTrackedPosition(futureContract)).mapTo[TrackedPosition], TimeOut.duration)
      trackedPosition.positionActor ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(futureContract, Position(1), PositionDynamics(buys = 1)))
    }

    "get all opened positions" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")

      positions ! assignedContents
      positions ! PositionUpdated(futureContract.id, Position(1), PositionDynamics(buys = 1))

      val openPositions = Await.result((positions ? GetPositions).mapTo[Positions], TimeOut.duration)
      assert(openPositions.positions.size == 1)
      assert(openPositions.positions(futureContract) == Position(1))
    }
  }

}