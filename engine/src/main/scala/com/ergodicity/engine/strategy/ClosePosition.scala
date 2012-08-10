package com.ergodicity.engine.strategy

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.util.duration._
import com.ergodicity.core.{WhenUnhandled, OrderType, Isin, Market}
import com.ergodicity.engine.Engine
import com.ergodicity.engine.service.{Broker, Positions}
import com.ergodicity.core.broker.{Broker => BrokerCore}
import akka.event.Logging
import com.ergodicity.core.position.Positions.{OpenPositions, GetOpenPositions}
import akka.util.Timeout
import akka.dispatch.Await
import com.ergodicity.core.Market.Futures


case object CloseAllPositionsStrategy extends Strategy

trait CloseAllPositions {
  engine: Engine with Broker with Positions =>

}

class CloseAllPositionsManager(engine: Engine with Positions) extends Actor with WhenUnhandled {
  val log = Logging(context.system, self)

  import Strategy._
  import engine._

  implicit val timeout = Timeout(1.second)

  protected def receive = start orElse stop orElse whenUnhandled

  private def start: Receive = {
    case Start =>
      log.info("Start CloseAllPositions strategy")
      val positions = Await.result((Positions ? GetOpenPositions).mapTo[OpenPositions], 1.second)
      log.info("Open positions = " + positions)
  }

  private def stop: Receive = {
    case Stop =>
      log.info("Stop CloseAllPositions strategy")
      context.stop(self)
  }
}

sealed trait ClosePositionState

object ClosePositionState {

}

class ClosePosition[M <: Market](Position: ActorRef, Broker: ActorRef) extends Strategy with Actor {

  // import BrokerCore._

  protected def receive = {
    case _ => // Buy[Futures](Isin("100"), 1, 100, OrderType.ImmediateOrCancel)(com.ergodicity.core.broker.Protocol.FutAddOrder)
  }
}