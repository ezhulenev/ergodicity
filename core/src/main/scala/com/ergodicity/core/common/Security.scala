package com.ergodicity.core.common

sealed trait Security {
  def isinId: Int

  def isin: String
}

case class BasicSecurity(isinId: Int, isin: String) extends Security

sealed trait Derivative extends Security

case class FutureContract(isin: String, shortIsin: String, isinId: Int, name: String) extends Derivative

case class OptionContract(isin: String, shortIsin: String, isinId: Int, name: String) extends Derivative