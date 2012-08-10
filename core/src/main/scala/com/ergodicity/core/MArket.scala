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
  }
}

trait IsinId {
  def id: Int

  override def toString = "IsinId(" + id + ")"

  override def hashCode() = id.hashCode

  override def equals(obj: Any) = obj.isInstanceOf[IsinId] && obj.asInstanceOf[IsinId].id.equals(id)
}

object Isin {
  def apply(value: String) = new Isin {
    val isin = value
  }
}

trait Isin {
  def isin: String

  override def toString = "Isin(" + isin + ")"

  override def hashCode() = isin.hashCode

  override def equals(obj: Any) = obj.isInstanceOf[Isin] && obj.asInstanceOf[Isin].isin.equals(isin)
}

object ShortIsin {

  def apply(value: String) = new ShortIsin {
    val shortIsin = value
  }
}

trait ShortIsin {
  def shortIsin: String

  override def toString = "ShortIsisn(" + shortIsin + ")"

  override def hashCode() = shortIsin.hashCode

  override def equals(obj: Any) = obj.isInstanceOf[ShortIsin] && obj.asInstanceOf[ShortIsin].shortIsin.equals(shortIsin)
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