package com.ergodicity.core.common


object IsinId {

  private[IsinId] case class IsinIdInternal(id: Int) extends IsinId

  def apply(id: Int) = IsinIdInternal(id)
}

trait IsinId {
  def id: Int
}

object Isin {

  private[Isin] case class IsinInternal(isin: String) extends Isin

  def apply(isin: String) = IsinInternal(isin)
}

trait Isin {
  def isin: String
}

object ShortIsin {

  private[ShortIsin] case class ShortIsinInternal(shortIsin: String) extends ShortIsin

  def apply(shortIsin: String) = ShortIsinInternal(shortIsin)
}

trait ShortIsin {
  def shortIsin: String
}

case class FullIsin(id: Int, isin: String, shortIsin: String) extends IsinId with Isin with ShortIsin