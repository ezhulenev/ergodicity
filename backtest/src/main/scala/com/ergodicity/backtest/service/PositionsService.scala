package com.ergodicity.backtest.service

import akka.actor.ActorRef
import com.ergodicity.cgate.scheme.Pos
import com.ergodicity.core.Security
import com.ergodicity.core.position.{PositionDynamics, Position}
import collection.mutable
import scalaz.Scalaz._
import com.ergodicity.backtest.service.PositionsService.ManagedPosition
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.backtest.cgate.DataStreamListenerStubActor.DispatchData

object PositionsService {

  case class ManagedPosition(security: Security, position: Position, dynamics: PositionDynamics)

  implicit def managedPosition2plaza(pos: ManagedPosition) = new {
    val (security, position, dynamics) = (pos.security, pos.position, pos.dynamics)

    def asPlazaRecord = {
      val buff = allocate(Size.Pos)

      val cgate = new Pos.position(buff)
      cgate.set_isin_id(security.id.id)
      cgate.set_pos(position.pos)
      cgate.set_open_qty(dynamics.open)
      cgate.set_buys_qty(dynamics.buys)
      cgate.set_sells_qty(dynamics.sells)
      cgate.set_net_volume_rur(dynamics.volume)
      dynamics.lastDealId.map(id => cgate.set_last_deal_id(id))

      cgate
    }
  }
}

class PositionsService(pos: ActorRef, initialPositions: Map[Security, (Position, PositionDynamics)] = Map())(implicit context: SessionContext) {

  import PositionsService._

  private[this] val RemoveRecordReplAct = 1

  private[this] val positions = mutable.Map[Security, (Position, PositionDynamics)](initialPositions.toSeq.map({
    case (security, (position, dynamics)) => (security, (position, dynamics.reset))
  }): _*)

  // Dispatch initial positions
  positions.foreach {
    case (security, (position, dynamics)) => dispatch(ManagedPosition(security, position, dynamics))
  }

  def bought(security: Security, amount: Int, dealId: Long) {
    assert(amount > 0, "Bought amount should be greater then 0")
    val (position, dynamics) = updatePosition(security, _ + Position(amount), _.bought(amount, dealId))
    dispatch(ManagedPosition(security, position, dynamics))
  }

  def sold(security: Security, amount: Int, dealId: Long) {
    assert(amount > 0, "Sold amount should be greater then 0")
    val (position, dynamics) = updatePosition(security, _ - Position(amount), _.sold(amount, dealId))
    dispatch(ManagedPosition(security, position, dynamics))
  }

  def discard(security: Security) {
    positions get security foreach {
      case (position, dynamics) if (position == Position.flat) =>
        dispatch(ManagedPosition(security, position, dynamics), RemoveRecordReplAct)
      case _ => throw new IllegalStateException("Can't discard non flat position for " + security)
    }
    positions - security
  }

  def current: Map[Security, (Position, PositionDynamics)] = positions.toMap

  private[this] def updatePosition(security: Security, updatePosition: Position => Position, updateDynamics: PositionDynamics => PositionDynamics): (Position, PositionDynamics) = {
    val (position, dynamics) = positions.getOrElseUpdate(security, (Position.flat, PositionDynamics.empty))
    val updated = (updatePosition(position), updateDynamics(dynamics))
    positions(security) = updated
    updated
  }

  private[this] def dispatch(position: ManagedPosition, replAct: Long = 0) {
    if (!assigned(position.security)) {
      throw new IllegalArgumentException("Security not assigned; Security = " + position.security)
    }
    val record = position.asPlazaRecord
    record.set_replAct(replAct)
    pos ! DispatchData(StreamData(Pos.position.TABLE_INDEX, record.getData) :: Nil)
  }

  private[this] def assigned: Security => Boolean = mutableHashMapMemo {
    security => context.futures.exists(_.isin == security.isin.isin) || context.options.exists(_.isin == security.isin.isin)
  }

}