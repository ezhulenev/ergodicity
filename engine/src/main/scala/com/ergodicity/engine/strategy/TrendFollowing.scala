package com.ergodicity.engine.strategy

import com.ergodicity.core.Isin
import com.ergodicity.engine.StrategyEngine
import akka.actor._
import com.ergodicity.engine.strategy.TrendFollowing.Trend
import org.joda.time.DateTime
import com.ergodicity.engine.strategy.PriceRegression.TimeSeries
import com.ergodicity.core.trade.Trade
import org.apache.commons.math3.stat.regression.SimpleRegression
import akka.util.duration._
import akka.util.Duration
import org.apache.commons.math3.stat.StatUtils

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

  case class PricePoint(time: DateTime, value: Double)

  case class DataPoint(x: Double, y: Double)

  case class TimeSeries(primary: Seq[PricePoint], secondary: Seq[PricePoint])

  case class PriceTrend(primary: Option[Slope], secondary: Option[Slope])

  case class Slope(value: Double, stdErr: Double, confidenceInterval: Double)

}


class PriceRegressionActor(reportTo: ActorRef)(primaryDuration: Duration = 1.minute, secondaryDuration: Duration = 1.minute) extends Actor with FSM[Any, TimeSeries] {

  import PriceRegression._

  case object Computing

  startWith(Computing, TimeSeries(Nil, Nil))

  when(Computing) {
    case Event(Trade(_, _, _, price, _, time, _), TimeSeries(primary, secondary)) =>
        val adjustedPrimary = primary.takeWhile(_.time.getMillis > (time.getMillis - primaryDuration.toMillis)) :+ PricePoint(time, price.toDouble)
        val primarySlope = slope(normalized(adjustedPrimary))

        val adjustedSecondary = primarySlope
          .map(slope => secondary.takeWhile(_.time.getMillis > (time.getMillis - secondaryDuration.toMillis)) :+ PricePoint(time, slope.value))
          .getOrElse(secondary.takeWhile(_.time.getMillis > (time.getMillis - secondaryDuration.toMillis)))
        val secondarySlope = slope(normalized(adjustedSecondary))

        reportTo ! PriceTrend(primarySlope, secondarySlope)

        stay() using TimeSeries(adjustedPrimary, adjustedSecondary)
      stay()
  }

  initialize

  def slope(data: Seq[DataPoint]): Option[Slope] = {
    if (data.size < 3) return None

    val regression = new SimpleRegression(false)
    data.foreach(point => regression.addData(point.x, point.y))
    Some(Slope(regression.getSlope, regression.getSlopeStdErr, regression.getSlopeConfidenceInterval))
  }

  def normalized(data: Seq[PricePoint]): Seq[DataPoint] = {
    val normalizedTime = StatUtils.normalize(data.map(_.time.getMillis.toDouble).toArray)
    val normalizedValued = StatUtils.normalize(data.map(_.value).toArray)
    normalizedTime zip normalizedValued map {
      case (t, v) => DataPoint(t, v)
    }
  }
}