package com.ergodicity.core

sealed trait Market

object Market {
  sealed trait Futures extends Market

  sealed trait Options extends Market
}

case class Isins(id: Int, isin: String, shortIsin: String) extends IsinId with Isin with ShortIsin

object IsinId {
  def apply(value: Int) = new IsinId {
    val id = value

    override def toString = "IsinId(" + id + ")"
  }
}

trait IsinId {
  def id: Int
}

object Isin {
  def apply(value: String) = new Isin {
    val isin = value

    override def toString = "Isin(" + isin + ")"
  }
}

trait Isin {
  def isin: String
}

object ShortIsin {

  def apply(value: String) = new ShortIsin {
    val shortIsin = value

    override def toString = "apply(" + shortIsin + ")"
  }
}

trait ShortIsin {
  def shortIsin: String
}


sealed trait Security {
  def isin: Isins
}

sealed trait Derivative extends Security

case class FutureContract(isin: Isins, name: String) extends Derivative

case class OptionContract(isin: Isins, name: String) extends Derivative


sealed trait OrderType

object OrderType {

  case object GoodTillCancelled extends OrderType

  case object ImmediateOrCancel extends OrderType

  case object FillOrKill extends OrderType

}


sealed trait OrderDirection

object OrderDirection {

  case object Buy extends OrderDirection

  case object Sell extends OrderDirection

}