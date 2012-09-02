package com.ergodicity.engine.strategy

import akka.actor.Props
import com.ergodicity.engine.StrategyEngine.EngineConfig

trait StrategyId

abstract class StrategyBuilder(val id: StrategyId) {
  def props(implicit config: EngineConfig): Props
}

object StrategiesFactory {
  def empty = new StrategiesFactory {
    def strategies = Nil
  }
}

trait StrategiesFactory {
  factory =>

  def strategies: Iterable[StrategyBuilder]

  def &(other: StrategiesFactory) = new StrategiesFactory {
    def strategies = factory.strategies ++ other.strategies
  }

  implicit def enrichProps(p: Props)(implicit id: StrategyId) = new StrategyBuilder(id) {
    def props(implicit config: EngineConfig) = p
  }
}



