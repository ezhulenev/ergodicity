package com.ergodicity.engine.strategy

import akka.actor.Actor
import com.ergodicity.engine.StrategyEngine

trait Strategy {
  strategy: Actor =>

  def engine: StrategyEngine
}
