package com.ergodicity.engine

import strategy.{StrategiesFactory, StrategyId}
import akka.actor.ActorRef
import com.ergodicity.engine.StrategyEngine.ManagedStrategy
import collection.mutable

object StrategyEngine {

  case class ManagedStrategy(id: StrategyId, ref: ActorRef)

  trait Notifier {

  }
}

class StrategyEngine(factory: StrategiesFactory) {

  protected[engine] val strategies = mutable.Map[StrategyId, ManagedStrategy]()

}