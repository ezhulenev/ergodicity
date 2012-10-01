package com.ergodicity.engine.strategy

import com.ergodicity.core.Isin
import com.ergodicity.engine.StrategyEngine
import akka.actor._
import com.ergodicity.engine.strategy.TrendFollowing.Trend
import akka.util.Duration
import org.joda.time.DateTime
import com.ergodicity.engine.strategy.PriceSlopeCalculator.TimeSeries

object TrendFollowing {

  case class TrendFollowing(isin: Isin) extends StrategyId

  sealed trait Trend

  case object Bullish extends Trend

  case object Bearish extends Trend

  case object Flat extends Trend

  def apply(isin: Isin) = new StrategiesFactory {

    implicit val id = TrendFollowing(isin)

    def strategies = enrichProps(strategy _) :: Nil

    def strategy(engine: StrategyEngine) = Props(new Actor {
      protected def receive = null
    })
  }
}

class TrendFollowingStrategy(val engine: StrategyEngine)(implicit id: StrategyId) extends Actor with LoggingFSM[Trend, Unit] with Strategy {

  import TrendFollowing._

  startWith(Flat, ())

  initialize
}

object PriceSlopeCalculator {
  case class Point(time: DateTime, value: Double)

  case class TimeSeries(price: Seq[Point], slope: Seq[Point])

  case class Slope(price: Double, slope: Double)
}


class PriceSlopeCalculator(price: Duration, slope: Duration) extends Actor with ActorLogging {
  var timeSeries = TimeSeries(Seq(), Seq())

  protected def receive = null
}