package com.ergodicity.engine.strategy

import akka.actor.Props

trait StrategyId

case class Strategy(props: Props)(implicit val id: StrategyId)

trait StrategiesFactory {
  factory =>

  def apply(): Iterable[Strategy]

  def &(other: StrategiesFactory) = new StrategiesFactory {
    def apply() = factory.apply() ++ other.apply()
  }
}
