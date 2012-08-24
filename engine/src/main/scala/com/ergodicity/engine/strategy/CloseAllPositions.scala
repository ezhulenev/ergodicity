package com.ergodicity.engine.strategy

import com.ergodicity.engine.{Engine, Services}
import com.ergodicity.engine.service.InstrumentDataService
import akka.actor.Actor

object CloseAllPositions {
  def apply(e: Engine with Services with InstrumentDataService) = new CloseAllPositions {
    def engine = e
  }
}

trait CloseAllPositions extends Strategy with Actor {
  def engine: Engine with Services with InstrumentDataService

  protected def receive = null
}