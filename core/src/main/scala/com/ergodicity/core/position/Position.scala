package com.ergodicity.core.position

sealed trait Direction

case object Long extends Direction

case object Short extends Direction

case object Flat extends Direction

object Position {
  def flat = Position(0)
}

case class Position(pos: Int) {
  def dir = if (pos == 0) Flat else if (pos < 0) Short else Long
}

object PositionDynamics {
  def empty = PositionDynamics()
}
case class PositionDynamics(open: Int = 0, buys: Int = 0, sells: Int = 0, volume: BigDecimal = 0, lastDealId: Option[scala.Long] = None) {
  def aggregated = Position(open + buys - sells)
}