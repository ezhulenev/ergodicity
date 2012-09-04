package com.ergodicity.engine.strategy

import akka.testkit.{TestActorRef, TestFSMRef, ImplicitSender, TestKit}
import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core._
import com.ergodicity.core.session.Instrument
import position.Position
import session.Instrument.Limits
import session.SessionActor.AssignedInstruments
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.Pos
import com.ergodicity.cgate.DataStream
import com.ergodicity.cgate.repository.Repository.Snapshot
import com.ergodicity.core.PositionsTrackingState.Online
import com.ergodicity.engine.{Services, StrategyEngine}
import org.mockito.Mockito._
import com.ergodicity.engine.service.Portfolio

class CloseAllPositionsSpec  extends TestKit(ActorSystem("CloseAllPositionsSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val isin1 = Isin("RTS-9.12")
  implicit val isinId1 = IsinId(100)

  implicit val isin2 = Isin("RTS-12.12")
  implicit val isinId2 = IsinId(101)

  val assignedInstruments = AssignedInstruments(Set(
    Instrument(FutureContract(isinId1, isin1, ShortIsin(""), "Future Contract #1"), Limits(0, 0)),
    Instrument(FutureContract(isinId2, isin2, ShortIsin(""), "Future Contract #2"), Limits(0, 0))
  ))

  def positionRecord(pos:Int, open: Int = 0, buys: Int = 0, sells: Int = 0)(id: IsinId) = {
    val buff = ByteBuffer.allocate(1000)
    val position = new Pos.position(buff)
    position.set_isin_id(id.id)
    position.set_open_qty(open)
    position.set_buys_qty(buys)
    position.set_sells_qty(sells)
    position.set_pos(pos)
    position.set_net_volume_rur(new java.math.BigDecimal(100))
    position
  }

  "Close All Positions" must {
    "load all current positions" in {
      // Use PositionsTracking as Portfolio service
      val portfolio = TestFSMRef(new PositionsTracking(TestFSMRef(new DataStream, "DataStream")), "Portfolio")
      portfolio.setState(Online)
      portfolio ! assignedInstruments
      portfolio ! Snapshot(
        portfolio.underlyingActor.asInstanceOf[PositionsTracking].PositionsRepository,
        positionRecord(3, buys = 5, sells = 2)(isinId2) :: positionRecord(1, buys = 1)(isinId1) :: Nil
      )

      // Prepare mock for engine and services
      val engine = mock(classOf[StrategyEngine])
      val services = mock(classOf[Services])
      when(engine.services).thenReturn(services)
      when(services.service(Portfolio.Portfolio)).thenReturn(portfolio)

      // Build strategy
      val strategy = TestActorRef(new CloseAllPositions(engine), "CloseAllPositions")
      val underlying = strategy.underlyingActor

      assert(underlying.positions.size == 2)
      assert(underlying.positions(isin1) == Position(1))
      assert(underlying.positions(isin2) == Position(3))
    }
  }
}