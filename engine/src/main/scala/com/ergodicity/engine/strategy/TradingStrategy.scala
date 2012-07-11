package com.ergodicity.engine.strategy

import com.ergodicity.core.common.Security

trait TradingStrategy {
  def security: Security
}