package com.ergodicity.engine.strategy

import com.ergodicity.core.Isin
import akka.actor.Props
import com.ergodicity.engine.Services

abstract class StrategyFactory(family: StrategyFamily) {
  def apply(resolver: Services.Resolver): Seq[Strategy]
}

trait Strategy {
  def isin: Isin

  def apply: Props
}