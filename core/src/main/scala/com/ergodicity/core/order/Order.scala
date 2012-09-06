package com.ergodicity.core.order

import com.ergodicity.core.{OrderDirection, OrderType, IsinId}

case class Order(id: Long, sessionId: Int, isin: IsinId, orderType: OrderType, direction: OrderDirection, price: BigDecimal, amount: Int)

