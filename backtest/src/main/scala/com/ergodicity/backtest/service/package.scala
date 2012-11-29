package com.ergodicity.backtest

import java.math
import java.nio.{ByteOrder, ByteBuffer}

package object service {

  object Size {
    val Session = 144
    val Future = 396
    val Option = 366
    val SysEvent = 105
    val Pos = 92
    val FutTrade = 282
    val OptTrade = 270
    val OrdLog = 100
    val OrdBook = 50
    val FutOrder = 210
    val OptOrder = 210
  }

  protected[service] implicit def toJbd(v: BigDecimal): java.math.BigDecimal = new math.BigDecimal(v.toString())

  protected[service] def allocate(size: Int) = {
    val buff = ByteBuffer.allocate(size)
    buff.order(ByteOrder.nativeOrder())
    buff
  }
}
