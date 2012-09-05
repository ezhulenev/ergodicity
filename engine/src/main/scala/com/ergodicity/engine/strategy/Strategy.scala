package com.ergodicity.engine.strategy

import akka.actor.Actor
import com.ergodicity.engine.StrategyEngine

object Strategy {
  case object Start
  case object Stop
}

trait Strategy {
  strategy: Actor =>

  def engine: StrategyEngine
}
