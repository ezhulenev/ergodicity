package com.ergodicity.backtest.service

import akka.actor.ActorRef
import com.ergodicity.backtest.cgate.ListenerStubActor.Dispatch
import com.ergodicity.backtest.service.PositionsService.{DiscardedPosition, OpenedPosition}
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.scheme.Pos
import com.ergodicity.core.Security
import com.ergodicity.core.position.{PositionDynamics, Position}

object PositionsService {

  sealed trait ManagedPosition

  case class OpenedPosition(security: Security, position: Position, dynamics: PositionDynamics) extends ManagedPosition {

    def bought(amount: Int, dealId: Long)(implicit service: PositionsService): OpenedPosition = {
      assert(amount > 0, "Bought amount should be greater then 0")
      val updatedPosition = position + Position(amount)
      val updatedDynamics = dynamics.copy(buys = dynamics.buys + amount, lastDealId = Some(dealId))
      service.dispatch(copy(position = updatedPosition, dynamics = updatedDynamics))
    }

    def sold(amount: Int, dealId: Long)(implicit service: PositionsService): OpenedPosition = {
      assert(amount > 0, "Sold amount should be greater then 0")
      val updatedPosition = position - Position(amount)
      val updatedDynamics = dynamics.copy(sells = dynamics.sells + amount, lastDealId = Some(dealId))
      service.dispatch(copy(position = updatedPosition, dynamics = updatedDynamics))
    }

    def toggleSession()(implicit service: PositionsService): OpenedPosition = {
      service.dispatch(copy(dynamics = PositionDynamics(position.pos, buys = 0, sells = 0, lastDealId = None)))
    }

    def discard()(implicit service: PositionsService): DiscardedPosition = {
      service.discard(this)
    }
  }

  case class DiscardedPosition(security: Security) extends ManagedPosition

}

class PositionsService(pos: ActorRef) {
  private[this] implicit val Service = this

  private[this] val RemoveRecordReplAct = 1

  def open(security: Security, position: Position = Position.flat, dynamics: PositionDynamics = PositionDynamics.empty) = {
    val opened = new OpenedPosition(security, position, dynamics)
    dispatch(opened)
  }

  private[PositionsService] def discard(position: OpenedPosition): DiscardedPosition = {
    if (position.position != Position.flat) {
      throw new IllegalStateException("Can't discard non flat position")
    }

    val record = position.asPlazaRecord
    record.set_replAct(RemoveRecordReplAct)
    pos ! Dispatch(StreamData(Pos.position.TABLE_INDEX, record.getData) :: Nil)
    DiscardedPosition(position.security)
  }

  private[PositionsService] def dispatch(position: OpenedPosition): OpenedPosition = {
    val record = position.asPlazaRecord
    pos ! Dispatch(StreamData(Pos.position.TABLE_INDEX, record.getData) :: Nil)
    position
  }
}