package com.ergodicity.engine.strategy

import akka.actor.{ActorLogging, Props, Actor}

object CloseAllPositions {

  implicit case object CloseAllPositions extends StrategyId

  def apply() = new StrategiesFactory {

    def strategies = Props(new CloseAllPositions) :: Nil
  }
}

class CloseAllPositions extends Actor with ActorLogging {

  override def preStart() {
    log.info("Started CloseAllPositions")
  }

  protected def receive = null
}
