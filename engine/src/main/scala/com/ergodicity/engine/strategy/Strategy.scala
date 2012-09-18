package com.ergodicity.engine.strategy

import akka.actor.Actor
import com.ergodicity.engine.StrategyEngine
import com.ergodicity.engine.Services.ServiceFailedException
import com.ergodicity.engine.StrategyEngine.StrategyFailedException

object Strategy {
  case object Start
  case object Stop
}

trait Strategy {
  strategy: Actor =>

  def engine: StrategyEngine

  def failed(message: String)(implicit strategy: StrategyId): Nothing = {
    throw new StrategyFailedException(strategy, message)
  }
}
