package com.ergodicity.backtest.service

import akka.actor.ActorRef
import com.ergodicity.backtest.cgate.ListenerStubActor.Dispatch
import com.ergodicity.backtest.service.PositionsService.ManagedPosition
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.scheme.Pos
import com.ergodicity.core.Security
import com.ergodicity.core.position.{PositionDynamics, Position}
import scala.concurrent.stm._
import concurrent.stm.Ref

object PositionsService {

  case class ManagedPosition(security: Security, position: Position, dynamics: PositionDynamics)

}

class PositionsService(pos: ActorRef, initialPositions: Map[Security, (Position, PositionDynamics)] = Map()) {
  private[this] val RemoveRecordReplAct = 1

  private[this] val positions = Ref(initialPositions)

  private[this] val toggled = false

  // Dispatch initial positions
  initialPositions.foreach {
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
    atomic {
      implicit txn =>
        positions.transform {
          case p if (!p.contains(security)) => p

          case p if (p.contains(security) && p(security)._1 == Position.flat) =>
            val (position, dynamics) = p(security)
            dispatch(ManagedPosition(security, position, dynamics), RemoveRecordReplAct)
            p - security

          case _ => throw new IllegalStateException("Can't discard non flat position for " + security)
        }
    }
  }

  def toggleSession(): PositionsService = atomic {
    assert(!toggled, "Service already toggled to another session")

    implicit txn =>
      val toggledPositions = positions() mapValues {
        case (position, dynamics) =>
          (position, PositionDynamics(position.pos, buys = 0, sells = 0, lastDealId = None))
      }
      new PositionsService(pos, toggledPositions)
  }

  private[this] def updatePosition(security: Security, updatePosition: Position => Position, updateDynamics: PositionDynamics => PositionDynamics): (Position, PositionDynamics) = atomic {
    assert(!toggled, "Positions service toggled to another session")

    implicit txn =>
      positions.transform {
        positions =>
          val (position, dynamics) = positions.getOrElse(security, (Position.flat, PositionDynamics.empty))
          positions + (security ->(updatePosition(position), updateDynamics(dynamics)))
      }
      positions() apply security
  }


  private[this] def dispatch(position: ManagedPosition, replAct: Long = 0) {
    val record = position.asPlazaRecord
    record.set_replAct(replAct)
    pos ! Dispatch(StreamData(Pos.position.TABLE_INDEX, record.getData) :: Nil)
  }
}