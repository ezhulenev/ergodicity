package com.ergodicity


package object cgate {

  case class Signs(signs: Long) {

    sealed trait Type

    case object Margin extends Type

    case object Premium extends Type

    def eveningSession = (signs & 0x01) > 0

    def optionType = if ((signs & 0x02) == 1) Margin else Premium

    def spot = (signs & 0x04) > 0

    def mainSpot = (signs & 0x08) > 0

    def anonymous = (signs & 0x10) > 0

    def nonAnonymous = (signs & 0x20) > 0

    def primarySession = (signs & 0x40) > 0

    def multileg = (signs & 0x100) > 0

    def moneyMarket = (signs & 0x800) > 0
  }
}