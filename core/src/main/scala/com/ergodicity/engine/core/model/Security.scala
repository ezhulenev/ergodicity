package com.ergodicity.engine.core.model

sealed trait Security {
  def isin: String
}

sealed trait Derivative extends Security

case class FutureContract(isin: String, shortIsin: String, isinId: Long, name: String) extends Derivative

case class OptionContract(isin: String, shortIsin: String, isinId: Long, name: String) extends Derivative