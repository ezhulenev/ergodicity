package com.ergodicity.engine.strategy

import com.ergodicity.core.common.Security
import akka.actor.Actor

class CloseAllPositions(val security: Security) extends Actor with TradingStrategy {
  protected def receive = null
}