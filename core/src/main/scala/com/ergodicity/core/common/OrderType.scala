package com.ergodicity.core.common

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

