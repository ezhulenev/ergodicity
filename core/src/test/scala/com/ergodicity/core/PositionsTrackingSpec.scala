package com.ergodicity.core

import akka.pattern._
import akka.event.Logging
import akka.actor.ActorSystem
import java.util.concurrent.TimeUnit
import akka.dispatch.Await
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.Pos
import java.math.BigDecimal
import com.ergodicity.cgate.{DataStreamState, DataStream}
import org.scalatest.{BeforeAndAfter, GivenWhenThen, BeforeAndAfterAll, WordSpec}
import com.ergodicity.core.PositionsTracking._
import position.PositionActor.{PositionTransition, CurrentPosition, SubscribePositionUpdates}
import position.Position
import session.Instrument
import session.Instrument.Limits
import session.SessionActor.AssignedInstruments
import akka.actor.FSM.Transition
import com.ergodicity.cgate.repository.Repository.Snapshot
import com.ergodicity.core.PositionsTracking.OpenPositions
import com.ergodicity.core.PositionsTracking.TrackedPosition

class PositionsTrackingSpec extends TestKit(ActorSystem("PositionsTrackingSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll with BeforeAndAfter {
  val log = Logging(system, self)

  implicit val TimeOut = akka.util.Timeout(100, TimeUnit.MILLISECONDS)

  override def afterAll() {
    system.shutdown()
  }

  after {
    Thread.sleep(100)
  }

  val isin = Isin("RTS-9.12")
  val isinId = IsinId(100)

  val assignedInstruments = AssignedInstruments(Set(Instrument(FutureContract(isinId, isin, ShortIsin(""), "Future Contract"), Limits(0, 0))))

  val position = {
    val buff = ByteBuffer.allocate(1000)
    val pos = new Pos.position(buff)
    pos.set_isin_id(isinId.id)
    pos.set_buys_qty(1)
    pos.set_pos(1)
    pos.set_net_volume_rur(new BigDecimal(100))
    pos
  }

  val updatedPosition = {
    val buff = ByteBuffer.allocate(1000)
    val pos = new Pos.position(buff)
    pos.set_isin_id(isinId.id)
    pos.set_buys_qty(2)
    pos.set_pos(2)
    pos.set_net_volume_rur(new BigDecimal(200))
    pos
  }

  "Positions Tracking" must {

    import PositionsTrackingState._

    "initialized in Binded state" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      assert(positions.stateName == Binded)
    }

    "bind to stream and go to LoadingPositions later" in {
      val PosDS = TestFSMRef(new DataStream, "DataStream")
      val positions = TestFSMRef(new PositionsTracking(PosDS), "Positions")

      when("PosRepl data Streams goes online")
      positions ! Transition(PosDS, DataStreamState.Opened, DataStreamState.Online)

      then("should go to LoadingPositions state")
      assert(positions.stateName == LoadingPositions)
    }

    "go to online state after first snapshot received" in {
      val PosDS = TestFSMRef(new DataStream, "DataStream")
      val positions = TestFSMRef(new PositionsTracking(PosDS), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      positions ! assignedInstruments

      when("PosRepl data Streams goes online")
      positions ! Transition(PosDS, DataStreamState.Opened, DataStreamState.Online)

      then("should go to LoadingPositions state")
      assert(positions.stateName == LoadingPositions)

      when("first snapshot received")
      positions ! Snapshot(underlying.PositionsRepository, position :: Nil)

      then("should go to Online state")
      assert(positions.stateName == Online)

      and("hande snapshot values")
      assert(underlying.positions.size == 1)
    }

    "handle first repository snapshot" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      positions.setState(Online)
      positions ! assignedInstruments

      val snapshot = Snapshot(underlying.PositionsRepository, position :: Nil)
      positions ! snapshot

      assert(underlying.positions.size == 1)

      val positionRef = underlying.positions(isin)
      positionRef ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(isin, Position(1)))
    }

    "close discarded positions" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      positions.setState(Online)

      positions ! assignedInstruments
      positions ! Snapshot(underlying.PositionsRepository, position :: Nil)

      assert(underlying.positions.size == 1)

      val positionRef = underlying.positions(isin)
      positionRef ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(isin, Position(1)))

      positions ! Snapshot(underlying.PositionsRepository, List[Pos.position]())

      assert(underlying.positions.size == 1)
      expectMsg(PositionTransition(isin, Position(1), Position.flat))
    }

    "update existing positions" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      positions.setState(Online)
      positions ! assignedInstruments
      positions ! Snapshot(underlying.PositionsRepository, position :: Nil)

      assert(underlying.positions.size == 1)

      val positionRef = underlying.positions(isin)
      positionRef ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(isin, Position(1)))

      positions ! Snapshot(underlying.PositionsRepository, updatedPosition :: Nil)
      expectMsg(PositionTransition(isin, Position(1), Position(2)))
    }

    "create new positions if it doesn't exists" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]
      positions.setState(Online)

      val position = Await.result((positions ? GetPositionActor(isin)).mapTo[TrackedPosition], TimeOut.duration)

      assert(underlying.positions.size == 1)

      position.positionActor ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(isin, Position.flat))
    }

    "return existing position on track position event" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      positions.setState(Online)
      positions ! assignedInstruments

      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      positions ! Snapshot(underlying.PositionsRepository, position :: Nil)

      val trackedPosition = Await.result((positions ? GetPositionActor(isin)).mapTo[TrackedPosition], TimeOut.duration)
      trackedPosition.positionActor ! SubscribePositionUpdates(self)
      expectMsg(CurrentPosition(isin, Position(1)))
    }

    "get all opened positions" in {
      val positions = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Positions")
      positions.setState(Online)
      positions ! assignedInstruments

      val underlying = positions.underlyingActor.asInstanceOf[PositionsTracking]

      positions ! Snapshot(underlying.PositionsRepository, position :: Nil)

      val openPositions = Await.result((positions ? GetOpenPositions).mapTo[OpenPositions], TimeOut.duration)
      assert(openPositions.positions.size == 1)
      assert(openPositions.positions.iterator.next() == isin)
    }
  }

}