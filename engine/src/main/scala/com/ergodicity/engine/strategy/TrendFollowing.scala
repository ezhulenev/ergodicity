package com.ergodicity.engine.strategy

import com.ergodicity.core.Isin
import com.ergodicity.engine.StrategyEngine
import akka.actor._
import com.ergodicity.engine.strategy.TrendFollowing.Trend
import org.joda.time.DateTime
import com.ergodicity.engine.strategy.PriceRegression.TimeSeries
import com.ergodicity.core.trade.Trade
import org.apache.commons.math3.stat.regression.SimpleRegression
import org.apache.commons.math3.stat.StatUtils
import akka.util.Duration
import akka.util.duration._

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

  case class RawData(time: DateTime, value: Double)

  case class NormalizedData(x: Double, y: Double)

  case class TimeSeries(primary: Seq[RawData], secondary: Seq[RawData])

  case class PriceSlope(primary: Double, secondary: Double)

}


class PriceRegressionActor(reportTo: ActorRef)(primaryDuration: Duration = 1.minute, secondaryDuration: Duration = 1.minute) extends Actor with FSM[Any, TimeSeries] {

  import PriceRegression._

  case object Computing

  startWith(Computing, TimeSeries(Nil, Nil))

  when(Computing) {
    case Event(Trade(_, _, _, price, _, time, _), TimeSeries(primary, secondary)) =>
      val adjustedPrimary = primary.dropWhile(_.time.getMillis < (time.getMillis - primaryDuration.toMillis)) :+ RawData(time, price.toDouble)
      val primarySlope = slope(adjustedPrimary)
      val adjustedSecondary = (secondary.dropWhile(_.time.getMillis < (time.getMillis - secondaryDuration.toMillis)) :+ RawData(time, primarySlope)).filterNot(_.value == Double.NaN)
      val secondarySlope = slope(adjustedSecondary)

      reportTo ! PriceSlope(primarySlope, secondarySlope)

      stay() using TimeSeries(adjustedPrimary, adjustedSecondary)
  }

  initialize

  def slope(data: Seq[RawData]): Double = {
    val regression = new SimpleRegression(false)
    normalized(data) foreach (point => regression.addData(point.x, point.y))
    regression.getSlope
  }

  def normalized(data: Seq[RawData]): Seq[NormalizedData] = {
    val normalizedTime = StatUtils.normalize(data.map(_.time.getMillis.toDouble).toArray)
    val normalizedValued = StatUtils.normalize(data.map(_.value).toArray)
    normalizedTime zip normalizedValued map {
      case (t, v) => NormalizedData(t, v)
    }
  }
}