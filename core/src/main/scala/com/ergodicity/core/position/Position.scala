package com.ergodicity.core.position

sealed trait Position {
  protected def abs: Int

  def +(pos: Position) = Position.apply(abs + pos.abs)
}

object Position {
  def apply(amount: Int) = {
    if (amount == 0)
      Flat
    else if (amount > 0)
      Long(amount)
    else
      Short(amount.abs)
  }
}

case class Long(position: Int) extends Position {
  if (position <= 0) throw new IllegalArgumentException("Position should be greater then 0")

  protected  def abs = position
}

case class Short(position: Int) extends Position {
  if (position <= 0) throw new IllegalArgumentException("Position should be greater then 0")

  protected def abs = -position
}

case object Flat extends Position {
  protected  def abs = 0
}

case class PositionDynamics(open: Position = Flat, buys: Int = 0, sells: Int = 0, volume: BigDecimal = 0, lastDealId: Option[scala.Long] = None) {
  def aggregated = open + Position(buys) + Position(-sells)
}

