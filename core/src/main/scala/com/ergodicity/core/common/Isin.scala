package com.ergodicity.core.common


object IsinId {

  private[IsinId] case class IsinIdInternal(id: Int) extends IsinId

  def apply(id: Int) = IsinIdInternal(id)
}

trait IsinId {
  def id: Int
}

object IsinCode {

  private[IsinCode] case class IsinCodeInternal(code: String) extends IsinCode

  def apply(code: String) = IsinCodeInternal(code)
}

trait IsinCode {
  def code: String
}

object IsinShortCode {

  private[IsinShortCode] case class IsinShortCodeInternal(shortCode: String) extends IsinShortCode

  def apply(shortCode: String) = IsinShortCodeInternal(shortCode)
}

trait IsinShortCode {
  def shortCode: String
}

case class Isin(id: Int, code: String, shortCode: String) extends IsinId with IsinCode with IsinShortCode