package com.ergodicity.core.position

import scalaz._
import Scalaz._

sealed trait Direction

case object Long extends Direction

case object Short extends Direction

case object Flat extends Direction

object Position {
  def flat = Position(0)

  implicit val PositionSemigroup: scalaz.Semigroup[Position] = semigroup((a, b) => Position(a.pos + b.pos))
}

case class Position(pos: Int) {
  def dir = if (pos == 0) Flat else if (pos < 0) Short else Long

  def -(other: Position) = Position(pos - other.pos)

  def +(other: Position) = Position(pos + other.pos)
}

object PositionDynamics {
  def empty = PositionDynamics()
}

case class PositionDynamics(open: Int = 0, buys: Int = 0, sells: Int = 0, volume: BigDecimal = 0, lastDealId: Option[scala.Long] = None) {
  def aggregated = Position(open + buys - sells)

  def bought(amount: Int, dealId: Long) = {
    assert(amount > 0)
    copy(buys = buys + amount, lastDealId = Some(dealId))
  }

  def sold(amount: Int, dealId: Long) = {
    assert(amount > 0)
    copy(sells = sells + amount, lastDealId = Some(dealId))
  }

  def reset = PositionDynamics(aggregated.pos, buys = 0, sells = 0, volume = 0, lastDealId = None)
}