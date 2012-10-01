package com.ergodicity.engine.strategy

import com.ergodicity.core.Isin
import com.ergodicity.engine.StrategyEngine
import akka.actor._
import com.ergodicity.engine.strategy.TrendFollowing.Trend
import org.joda.time.{Duration, DateTime}
import com.ergodicity.engine.strategy.PriceRegression.{Point}
import com.ergodicity.core.trade.Trade
import org.scala_tools.time.Implicits._
import org.apache.commons.math3.stat.regression.SimpleRegression

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

object PriceRegression {

  case class Point(time: DateTime, value: Double)

  case class Slope(primary: Double, secondary: Double)
}


class PriceRegression(primaryDuration: Duration, secondaryDuration: Duration) extends Actor with ActorLogging {
  var primaryTimeSeries = Seq[Point]()
  var secondaryTimeSeries = Seq[Point]()

  protected def receive = {
    case Trade(_, _, _, price, _, time, _) =>
      primaryTimeSeries = Point(time, price.toDouble) +: primaryTimeSeries.takeWhile(_.time < (time + primaryDuration))


  }

  def primaryRegression = {
    val regression = new SimpleRegression(false)
    val start = primaryTimeSeries.headOption

    primaryTimeSeries.reverse.foreach(point => primaryRegression.addData(point.time.getMillis - start, point.value))
  }


}