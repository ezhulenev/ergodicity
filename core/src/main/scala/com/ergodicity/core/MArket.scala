package com.ergodicity.core

sealed trait Market

object Market {

  sealed trait Futures extends Market

  sealed trait Options extends Market

}

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

  def toActorName = isin.replaceAll(" ", "@")
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
  def id: IsinId

  def isin: Isin

  def shortIsin: ShortIsin

  def name: String
}

sealed trait Derivative extends Security

case class FutureContract(id: IsinId, isin: Isin, shortIsin: ShortIsin, name: String) extends Derivative

case class OptionContract(id: IsinId, isin: Isin, shortIsin: ShortIsin, name: String) extends Derivative


sealed trait OrderType

object OrderType {

  case object GoodTillCancelled extends OrderType

  case object ImmediateOrCancel extends OrderType

  case object FillOrKill extends OrderType

  val GoodTillCancelledMask = 0x01
  val ImmediateOrCancelMask = 0x02

  def apply(status: Int) = status match {
    case t if ((t & 0x01) > 0) => OrderType.GoodTillCancelled
    case t if ((t & 0x02) > 0) => OrderType.ImmediateOrCancel
    case t => throw new IllegalArgumentException("Illegal order type: " + t)
  }

  implicit def toInt(tp: OrderType) = tp match {
    case GoodTillCancelled => GoodTillCancelledMask
    case ImmediateOrCancel => ImmediateOrCancelMask
    case FillOrKill => throw new IllegalArgumentException("Unsupported FillOrKill translation")
  }
}


sealed trait OrderDirection

object OrderDirection {

  case object Buy extends OrderDirection

  case object Sell extends OrderDirection

  implicit def toShort(direction: OrderDirection): Short = direction match {
    case Buy => 1
    case Sell => 2
  }

}