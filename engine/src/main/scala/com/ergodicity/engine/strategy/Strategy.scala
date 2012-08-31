package com.ergodicity.engine.strategy

import akka.actor.Props
import com.ergodicity.engine.StrategyEngine.EngineConfig

trait StrategyId

case class Strategy(id: StrategyId, props: Props)

object StrategiesFactory {
  def empty = new StrategiesFactory {
    def strategies(implicit config: EngineConfig) = Nil
  }
}

trait StrategiesFactory {
  factory =>

  def strategies(implicit config: EngineConfig): Iterable[Strategy]

  def &(other: StrategiesFactory) = new StrategiesFactory {
    def strategies(implicit config: EngineConfig) = factory.strategies ++ other.strategies
  }

  implicit def props2strategy(props: Props)(implicit id: StrategyId) = Strategy(id, props)
}
