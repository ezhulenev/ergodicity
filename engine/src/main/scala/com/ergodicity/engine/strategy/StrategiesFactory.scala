package com.ergodicity.engine.strategy

import akka.actor.Props
import com.ergodicity.engine.StrategyEngine

trait StrategyId

abstract class StrategyBuilder(val id: StrategyId) {
  def props(implicit engine: StrategyEngine): Props
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

  implicit def enrichProps(builder: StrategyEngine => Props)(implicit id: StrategyId) = new StrategyBuilder(id) {
    def props(implicit engine: StrategyEngine) = builder(engine)
  }
}



