package com.ergodicity.engine.strategy

import com.ergodicity.core.Isin
import com.ergodicity.engine.StrategyEngine
import akka.actor.{Actor, Props}

object TrendFollowing {
  case class TrendFollowing(isin: Isin) extends StrategyId

  def apply(isin: Isin) = new StrategiesFactory {

    implicit val id = TrendFollowing(isin)

    def strategies = enrichProps(strategy _) :: Nil

    def strategy(engine: StrategyEngine) = Props(new Actor {
      protected def receive = null
    })
  }
}