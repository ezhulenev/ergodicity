package com.ergodicity.engine.strategy

import com.ergodicity.core.{Security, Isin}
import com.ergodicity.engine.StrategyEngine
import akka.actor._
import org.joda.time.DateTime
import com.ergodicity.core.trade.Trade
import org.apache.commons.math3.stat.regression.SimpleRegression
import org.apache.commons.math3.stat.StatUtils
import akka.util.Duration
import akka.util.duration._
import com.ergodicity.engine.strategy.PriceRegression.{PriceSlope, TimeSeries}
import com.ergodicity.engine.service.TradesData
import com.ergodicity.core.trade.TradesTracking.SubscribeTrades
import com.ergodicity.engine.strategy.Strategy.Start
import akka.util
import com.ergodicity.engine.strategy.TrendFollowingState.{Bearish, Bullish, Flat, Trend}
import com.ergodicity.engine.strategy.PositionManagement.{PositionBalanced, PositionManagerStarted}
import com.ergodicity.core.position.Position

object TrendFollowing {

  implicit def toInterval(tuple: (Double, Double)): Interval = Interval(tuple._1, tuple._2)

  implicit def toDoubleInterval(tuple: ((Double, Double), (Double, Double))): (Interval, Interval) = (Interval(tuple._1._1, tuple._1._2), Interval(tuple._2._1, tuple._2._2))

  case class Interval(from: Double, to: Double) {
    assert(from < to, "'To' value must be greater then 'from' value, actual to = " + to + ", from = " + from)

    def contains(value: Double) = value >= from & value <= to
  }

  case class TrendFollowing(isin: Isin) extends StrategyId {
    override def toString = "TrendFollowing:" + isin.toActorName
  }

  def apply(isin: Isin, primaryDuration: Duration = 1.minute, secondaryDuration: Duration = 1.minute,
            bullishIndicator: (Interval, Interval),
            bearishIndicator: (Interval, Interval),
            flatIndicator: (Interval, Interval)) = new SingleStrategyFactory {
    implicit val id = TrendFollowing(isin)

    val strategy = new StrategyBuilder(id) {
      def props(implicit engine: StrategyEngine) = {
        val config = TrendFollowingConfig(primaryDuration, secondaryDuration) {
          case (_, PriceSlope(_, _, p, s)) if ((bullishIndicator._1 contains p) && (bullishIndicator._2 contains s)) => Bullish
          case (_, PriceSlope(_, _, p, s)) if ((bearishIndicator._1 contains p) && (bearishIndicator._2 contains s)) => Bearish
          case (_, PriceSlope(_, _, p, s)) if ((flatIndicator._1 contains p) && (flatIndicator._2 contains s)) => Flat
        }
        Props(new TrendFollowingStrategy(isin, config)(id, engine))
      }
    }
  }
}

sealed trait TrendFollowingState

object TrendFollowingState {

  case object WaitingPositionManager extends TrendFollowingState

  case object Ready extends TrendFollowingState

  case object WarmingUp extends TrendFollowingState

  case object Stopping extends TrendFollowingState

  sealed trait Trend extends TrendFollowingState

  case object Flat extends Trend

  case object Bullish extends Trend

  case object Bearish extends Trend

}

case class TrendFollowingConfig(primaryDuration: Duration, secondaryDuration: Duration)(val trend: PartialFunction[(Trend, PriceSlope), Trend])

class TrendFollowingStrategy(isin: Isin, config: TrendFollowingConfig)
                            (implicit id: StrategyId, val engine: StrategyEngine) extends Actor with LoggingFSM[TrendFollowingState, Option[Security]] with Strategy with InstrumentWatcher with PositionManagement {

  import TrendFollowingState._

  implicit val timeout = util.Timeout(1.second)

  implicit val PositionManager = PositionManagementConfig(self)

  // Services
  val tradesData = engine.services(TradesData.TradesData)

  // Regression calculation
  val priceRegression = context.actorOf(Props(new PriceRegression(self)(config.primaryDuration, config.secondaryDuration)), "PriceRegression")

  // Position manager
  val positionManager = managePosition(isin)

  var initialSlope: Option[PriceSlope] = None

  override def preStart() {
    log.info("Start trend following for isin = " + isin)
  }

  startWith(WaitingPositionManager, None)

  when(WaitingPositionManager, stateTimeout = 30.seconds) {
    case Event(PositionManagerStarted(i, security, position), _) if (i == isin) =>
      goto(Ready) using Some(security)

    case Event(StateTimeout, _) => failed("Failed to catch instrument for isin = " + isin)
  }

  when(Ready) {
    case Event(Start, Some(security)) =>
      log.info("Start {} strategy", id)
      tradesData ! SubscribeTrades(priceRegression, security)
      goto(WarmingUp)
  }


  when(WarmingUp) {
    case Event(slope: PriceSlope, _) if (initialSlope.isEmpty) =>
      initialSlope = Some(slope)
      stay()

    case Event(slope@PriceSlope(time, price, _, _), _) if (initialSlope.isDefined && !warmedUp(time)) =>
      stay()

    case Event(PriceSlope(time, price, _, _), _) if (initialSlope.isDefined && warmedUp(time)) =>
      log.info("Warmed up, start trading!")
      goto(Flat)
  }

  when(Flat) {
    case Event(slope: PriceSlope, _) =>
      goto(config.trend orElse defaultTrend apply(Flat, slope))
  }

  when(Bullish) {
    case Event(slope: PriceSlope, _) =>
      goto(config.trend orElse defaultTrend apply(Bullish, slope))
  }

  when(Bearish) {
    case Event(slope: PriceSlope, _) =>
      goto(config.trend orElse defaultTrend apply(Bearish, slope))
  }

  whenUnhandled {
    case Event(PositionBalanced(i, pos), _) if (i == isin) =>
      log.info("Position balanced! Position = "+pos)
      stay()
  }

  onTransition {
    case WaitingPositionManager -> Ready => engine.reportReady(Map())
    case _ -> Bullish =>
      log.info("Go to BULLISH trend")
      positionManager acquire Position(1)

    case _ -> Bearish =>
      log.info("Go to BEARISH trend")
      positionManager acquire Position(-1)

    case _ -> Flat =>
      log.info("Go to FLAT trend")
      positionManager acquire Position.flat
  }

  initialize

  private def warmedUp(time: DateTime) = true //initialSlope.get.time.getMillis < time.getMillis - math.max(config.primaryDuration.toMillis, config.primaryDuration.toMillis)

  def defaultTrend: PartialFunction[(Trend, PriceSlope), Trend] = {
    case (old, slope) => old
  }
}

object PriceRegression {

  private[PriceRegression] case class RawData(time: DateTime, value: Double)

  private[PriceRegression] case class NormalizedData(x: Double, y: Double)

  case class TimeSeries(primary: Seq[RawData], secondary: Seq[RawData])

  case class PriceSlope(time: DateTime, price: BigDecimal, primary: Double, secondary: Double)

}


class PriceRegression(reportTo: ActorRef)(primaryDuration: Duration = 1.minute, secondaryDuration: Duration = 1.minute) extends Actor with FSM[Any, TimeSeries] {

  import PriceRegression._

  val SecondaryScale = 100000

  case object Computing

  implicit def pimpRawData(data: Seq[RawData]) = new {
    def normalized: Seq[NormalizedData] = {
      val normalizedTime = StatUtils.normalize(data.map(_.time.getMillis.toDouble).toArray)
      val normalizedValued = StatUtils.normalize(data.map(_.value).toArray)
      normalizedTime zip normalizedValued map {
        case (t, v) => NormalizedData(t, v)
      }
    }
  }

  startWith(Computing, TimeSeries(Nil, Nil))

  when(Computing) {
    case Event(Trade(_, _, _, price, _, time, _), TimeSeries(primary, secondary)) =>
      val adjustedPrimary = primary.dropWhile(_.time.getMillis < (time.getMillis - primaryDuration.toMillis)) :+ RawData(time, price.toDouble)
      val primarySlope = slope(adjustedPrimary.normalized)
      val adjustedSecondary = (secondary.dropWhile(_.time.getMillis < (time.getMillis - secondaryDuration.toMillis)) :+ RawData(time, primarySlope * SecondaryScale)).filterNot(_.value.isNaN)
      val secondarySlope = slope(adjustedSecondary.normalized)

      reportTo ! PriceSlope(time, price, primarySlope, secondarySlope)

      stay() using TimeSeries(adjustedPrimary, adjustedSecondary)
  }

  initialize

  def slope(data: Seq[NormalizedData]): Double = {
    val regression = new SimpleRegression(false)
    data foreach (point => regression.addData(point.x, point.y))
    regression.getSlope
  }
}