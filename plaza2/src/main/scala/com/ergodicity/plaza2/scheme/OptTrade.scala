package com.ergodicity.plaza2.scheme

import com.ergodicity.plaza2.scheme.common.{OrderLogRecord => OrderLog}
import com.ergodicity.plaza2.scheme.common.{DealRecord => Deal}

object OptTrade {
  type OrdersLogRecord = OrderLog
  type DealRecord = Deal
}