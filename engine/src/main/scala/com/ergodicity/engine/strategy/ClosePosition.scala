package com.ergodicity.engine.strategy

import akka.actor.{ActorLogging, Props, Actor}
import com.ergodicity.engine.strategy.InstrumentWatchDog.WatchDogConfig
import com.ergodicity.engine.StrategyEngine

object CloseAllPositions {

  implicit case object CloseAllPositions extends StrategyId

  def apply() = new StrategiesFactory {

    def strategies = (strategy _ :: Nil)

    def strategy(engine: StrategyEngine) = Props(new CloseAllPositions(engine))
  }
}

class CloseAllPositions(val engine: StrategyEngine) extends Strategy with Actor with ActorLogging with InstrumentWatcher {

  implicit object watchingConfig extends WatchDogConfig(self, true, true)

  override def preStart() {
    log.info("Started CloseAllPositions")
  }

  protected def receive = null
}
