package com.ergodicity.core.trade

import com.ergodicity.core.IsinId
import org.joda.time.DateTime

case class Trade(id: Long, sessionId: Int, isin: IsinId, price: BigDecimal, amount: Int, time: DateTime, noSystem: Boolean)