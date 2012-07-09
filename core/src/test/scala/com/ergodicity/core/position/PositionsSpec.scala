package com.ergodicity.core.position

import akka.pattern._
import akka.event.Logging
import com.ergodicity.plaza2.scheme.Pos.PositionRecord
import com.ergodicity.plaza2.Repository.Snapshot
import com.ergodicity.core.common.IsinId
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack, CurrentState}
import akka.actor.{ActorRef, ActorSystem}
import java.util.concurrent.TimeUnit
import akka.dispatch.Await
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import org.mockito.Mockito._
import plaza2.{DataStream => P2DataStream}
import com.ergodicity.core.position.PositionsData.TrackingPositions
import com.ergodicity.plaza2.{DataStreamState, DataStream}
import com.ergodicity.core.position.Positions.BindPositions
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import com.ergodicity.core.AkkaConfigurations

class PositionsSpec extends TestKit(ActorSystem("PositionsSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll {
  val log = Logging(system, self)

  implicit val TimeOut = akka.util.Timeout(100, TimeUnit.MILLISECONDS)

  override def afterAll() {
    system.shutdown()
  }

  val isin = 101
  val position = PositionRecord(0, 0, 0, isin, "", 0, 1, 0, 1, BigDecimal(100), 0, BigDecimal(0))
  val updatedPosition = PositionRecord(0, 0, 0, isin, "", 0, 2, 0, 2, BigDecimal(200), 0, BigDecimal(0))

  "Positions" must {

    import PositionState._
    import PositionsState._

    "initialized in Idle state" in {
      val positionsDS = mock(classOf[P2DataStream])
      val positions = TestFSMRef(new Positions(TestFSMRef(DataStream(positionsDS))), "Positions")
      assert(positions.stateName == Idle)
    }

    "bind to stream and go Online later" in {
      val positionsDS = mock(classOf[P2DataStream])
      val PosDS = TestFSMRef(DataStream(positionsDS))
      val positions = TestFSMRef(new Positions(PosDS), "Positions")

      when("BindSessions received")
      positions ! BindPositions

      then("should go to Binded state")
      assert(positions.stateName == Binded)

      when("PosRepl data Streams goes online")
      positions ! Transition(PosDS, DataStreamState.Opening, DataStreamState.Online)

      then("should go to Online state")
      assert(positions.stateName == Online)
    }

    "handle first repository snapshot" in {
      val positionsDS = mock(classOf[P2DataStream])
      val positions = TestFSMRef(new Positions(TestFSMRef(DataStream(positionsDS))), "Positions")

      val underlying = positions.underlyingActor.asInstanceOf[Positions]

      positions.setState(Online, TrackingPositions())

      val snapshot = Snapshot(underlying.PositionsRepository, position :: Nil)
      positions ! snapshot

      assert(positions.stateData.asInstanceOf[TrackingPositions].positions.size == 1)

      val positionRef = positions.stateData.asInstanceOf[TrackingPositions].positions(IsinId(isin))
      positionRef ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(positionRef, OpenedPosition))
    }

    "terminate outdated positions" in {
      val positionsDS = mock(classOf[P2DataStream])
      val positions = TestFSMRef(new Positions(TestFSMRef(DataStream(positionsDS))), "Positions")

      val underlying = positions.underlyingActor.asInstanceOf[Positions]

      positions.setState(Online, TrackingPositions())

      positions ! Snapshot(underlying.PositionsRepository, position :: Nil)

      assert(positions.stateData.asInstanceOf[TrackingPositions].positions.size == 1)

      val positionRef = positions.stateData.asInstanceOf[TrackingPositions].positions(IsinId(isin))
      positionRef ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(positionRef, OpenedPosition))

      positions ! Snapshot(underlying.PositionsRepository, List[PositionRecord]())

      assert(positions.stateData.asInstanceOf[TrackingPositions].positions.size == 1)
      expectMsg(Transition(positionRef, OpenedPosition, UndefinedPosition))
    }

    "update existing positions" in {
      val positionsDS = mock(classOf[P2DataStream])
      val positions = TestFSMRef(new Positions(TestFSMRef(DataStream(positionsDS))), "Positions")

      val underlying = positions.underlyingActor.asInstanceOf[Positions]

      positions.setState(Online, TrackingPositions())
      positions ! Snapshot(underlying.PositionsRepository, position :: Nil)

      assert(positions.stateData.asInstanceOf[TrackingPositions].positions.size == 1)

      val positionRef = positions.stateData.asInstanceOf[TrackingPositions].positions(IsinId(isin))
      positionRef ! SubscribePositionUpdates(self)

      positions ! Snapshot(underlying.PositionsRepository, updatedPosition :: Nil)
      expectMsg(PositionUpdated(positionRef, PositionData(0, 2, 0, 2, BigDecimal(200), 0)))
    }

    "create new positions if it doesn't exists" in {
      val positionsDS = mock(classOf[P2DataStream])
      val positions = TestFSMRef(new Positions(TestFSMRef(DataStream(positionsDS))), "Positions")
      positions.setState(Online, TrackingPositions())

      val position = Await.result((positions ? TrackPosition(IsinId(isin))).mapTo[ActorRef], TimeOut.duration)

      assert(positions.stateData.asInstanceOf[TrackingPositions].positions.size == 1)

      position ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(position, UndefinedPosition))
    }

    "return existing position on track position event" in {
      val positionsDS = mock(classOf[P2DataStream])
      val positions = TestFSMRef(new Positions(TestFSMRef(DataStream(positionsDS))), "Positions")
      positions.setState(Online, TrackingPositions())

      val underlying = positions.underlyingActor.asInstanceOf[Positions]

      positions ! Snapshot(underlying.PositionsRepository, position :: Nil)

      val positionRef = Await.result((positions ? TrackPosition(IsinId(isin))).mapTo[ActorRef], TimeOut.duration)
      positionRef ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(positionRef, OpenedPosition))
    }
  }

}