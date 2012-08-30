package com.ergodicity.engine.strategy

import akka.actor.{ActorLogging, Props, Actor}
import com.ergodicity.core.Isin
import com.ergodicity.engine.Services.Resolver

object ClosePosition {

  implicit case object ClosePosition extends StrategyFamily

}

object ClosePositionFactory {

  import ClosePosition._

  def apply(isin: Isin) = new StrategyFactory(implicitly[StrategyFamily]) {
    def strategies() = new ClosePosition(isin) :: Nil
  }
}

class ClosePosition(isin: Isin) extends Strategy {

  import ClosePosition._

  val id = implicitly[StrategyId]

  def apply(resolver: Resolver) = Props(new ClosePositionStrategy(isin))
}

class ClosePositionStrategy(isin: Isin) extends Actor with ActorLogging {
  protected def receive = null

  override def preStart() {
    log.info("Started ClosePositionStrategy")
  }
}