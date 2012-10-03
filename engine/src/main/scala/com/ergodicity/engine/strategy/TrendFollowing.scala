package com.ergodicity.engine.strategy

import com.ergodicity.core.{Security, Isin}
import com.ergodicity.engine.StrategyEngine
import akka.actor._
import akka.pattern.ask
import org.joda.time.DateTime
import com.ergodicity.core.trade.Trade
import org.apache.commons.math3.stat.regression.SimpleRegression
import org.apache.commons.math3.stat.StatUtils
import akka.util.{Timeout, Duration}
import akka.util.duration._
import com.ergodicity.engine.strategy.PriceRegression.{PriceSlope, TimeSeries}
import com.ergodicity.engine.service.{InstrumentData, TradesData}
import com.ergodicity.core.trade.TradesTracking.SubscribeTrades
import com.ergodicity.core.session.SessionActor.{AssignedContents, GetAssignedContents}
import akka.dispatch.Await
import com.ergodicity.core.session.InstrumentNotAssigned
import com.ergodicity.engine.strategy.Strategy.Start

object TrendFollowing {

  case class TrendFollowing(isin: Isin) extends StrategyId {
    override def toString = "TrendFollowing:" + isin.toActorName
  }

  def apply(isin: Isin, primaryDuration: Duration = 1.minute, secondaryDuration: Duration = 1.minute) = new SingleStrategyFactory {
    implicit val id = TrendFollowing(isin)

    val strategy = new StrategyBuilder(id) {
      def props(implicit engine: StrategyEngine) = Props(new TrendFollowingStrategy(isin, primaryDuration, secondaryDuration)(id, engine))
    }
  }
}

sealed trait TrendFollowingState

object TrendFollowingState {

  case object Ready extends TrendFollowingState

  case object WarmingUp extends TrendFollowingState

  case object Parity extends TrendFollowingState

  case object Bullish extends TrendFollowingState

  case object Bearish extends TrendFollowingState

  case object Stopping extends TrendFollowingState

}

class TrendFollowingStrategy(isin: Isin, primaryDuration: Duration, secondaryDuration: Duration)(implicit id: StrategyId, val engine: StrategyEngine) extends Actor with LoggingFSM[TrendFollowingState, Unit] with Strategy {

  import TrendFollowingState._

  implicit val timeout = Timeout(1.second)

  val tradesData = engine.services(TradesData.TradesData)
  val instrumentData = engine.services(InstrumentData.InstrumentData)

  val security = getSecurity(5.seconds)

  val priceRegression = context.actorOf(Props(new PriceRegression(self)(primaryDuration, secondaryDuration)), "PriceRegression")

  override def preStart() {
    log.info("Trend following for security = " + security)
    engine.reportReady(Map())
  }

  startWith(Ready, ())

  when(Ready) {
    case Event(Start, _) =>
      log.info("Start {} strategy", id)
      tradesData ! SubscribeTrades(priceRegression, security)
      goto(WarmingUp)
  }


  when(WarmingUp) {
    case Event(slope: PriceSlope, _) =>
      log.info("Slope = {}", slope)
      stay()
  }

  initialize

  private def getSecurity(atMost: Duration): Security = {
    val future = (instrumentData ? GetAssignedContents).mapTo[AssignedContents]
    Await.result(future, atMost) ? isin getOrElse (throw new InstrumentNotAssigned(isin))
  }

}

object PriceRegression {

  private[PriceRegression] case class RawData(time: DateTime, value: Double)

  private[PriceRegression] case class NormalizedData(x: Double, y: Double)

  case class TimeSeries(primary: Seq[RawData], secondary: Seq[RawData])

  case class PriceSlope(time: DateTime, primary: Double, secondary: Double)

}


class PriceRegression(reportTo: ActorRef)(primaryDuration: Duration = 1.minute, secondaryDuration: Duration = 1.minute) extends Actor with FSM[Any, TimeSeries] {

  import PriceRegression._

  case object Computing

  startWith(Computing, TimeSeries(Nil, Nil))

  when(Computing) {
    case Event(Trade(_, _, _, price, _, time, _), TimeSeries(primary, secondary)) =>
      val adjustedPrimary = primary.dropWhile(_.time.getMillis < (time.getMillis - primaryDuration.toMillis)) :+ RawData(time, price.toDouble)
      val primarySlope = slope(adjustedPrimary)
      val adjustedSecondary = (secondary.dropWhile(_.time.getMillis < (time.getMillis - secondaryDuration.toMillis)) :+ RawData(time, primarySlope)).filterNot(_.value == Double.NaN)
      val secondarySlope = slope(adjustedSecondary)

      reportTo ! PriceSlope(time, primarySlope, secondarySlope)

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