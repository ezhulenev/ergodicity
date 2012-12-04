package com.ergodicity.backtest

import java.math
import java.nio.{ByteOrder, ByteBuffer}

package object service {

  object Size {
    // Replication
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

    // Command Replies
    val FORTS_MSG101 = 270
    val FORTS_MSG109 = 270
    val FORTS_MSG102 = 270
    val FORTS_MSG110 = 270
    val FORTS_MSG99  = 150
    val FORTS_MSG100 = 270
  }

  protected[service] implicit def toJbd(v: BigDecimal): java.math.BigDecimal = new math.BigDecimal(v.toString())

  protected[service] def allocate(size: Int) = {
    val buff = ByteBuffer.allocate(size)
    buff.order(ByteOrder.nativeOrder())
    buff
  }
}
