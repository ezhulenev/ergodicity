package com.ergodicity.core.common



sealed trait Security {
  def isin: FullIsin
}

sealed trait Derivative extends Security

case class FutureContract(isin: FullIsin, name: String) extends Derivative

case class OptionContract(isin: FullIsin, name: String) extends Derivative