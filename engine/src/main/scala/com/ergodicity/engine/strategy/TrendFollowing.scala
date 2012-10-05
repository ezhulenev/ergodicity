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
import com.ergodicity.core.session.{InstrumentState, InstrumentParameters}
import com.ergodicity.engine.strategy.Strategy.Start
import com.ergodicity.engine.strategy.InstrumentWatchDog.{CatchedParameters, CatchedState, Catched, WatchDogConfig}
import akka.util
import com.ergodicity.engine.strategy.TrendFollowingData.{UnderlyingInstrument, CatchingData}

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

  case object CatchingInstrument extends TrendFollowingState

  case object Ready extends TrendFollowingState

  case object WarmingUp extends TrendFollowingState

  case object Parity extends TrendFollowingState

  case object Bullish extends TrendFollowingState

  case object Bearish extends TrendFollowingState

  case object Stopping extends TrendFollowingState

}

sealed trait TrendFollowingData

object TrendFollowingData {

  case class CatchingData(security: Option[Security], parameters: Option[InstrumentParameters], state: Option[InstrumentState]) extends TrendFollowingData

  case class UnderlyingInstrument(security: Security, parameters: InstrumentParameters, state: InstrumentState) extends TrendFollowingData

}

class TrendFollowingStrategy(isin: Isin, primaryDuration: Duration, secondaryDuration: Duration)
                            (implicit id: StrategyId, val engine: StrategyEngine) extends Actor with LoggingFSM[TrendFollowingState, TrendFollowingData] with Strategy with InstrumentWatcher {

  import TrendFollowingState._

  implicit val timeout = util.Timeout(1.second)

  implicit val WatchDog = WatchDogConfig(self, notifyOnCatched = true, notifyOnParams = true, notifyOnState = true)

  // Services
  val tradesData = engine.services(TradesData.TradesData)

  // Regression calculation
  val priceRegression = context.actorOf(Props(new PriceRegression(self)(primaryDuration, secondaryDuration)), "PriceRegression")

  var initialSlope: Option[PriceSlope] = None

  override def preStart() {
    log.info("Start trend following for isin = " + isin)
    watchInstrument(isin)
  }

  startWith(CatchingInstrument, CatchingData(None, None, None))

  when(CatchingInstrument, stateTimeout = 30.seconds) {
    case Event(Catched(i, instrument), catching: CatchingData) if (i == isin) =>
      log.info("Catched assigned instrument; Isin = {}, session = {}, security = {}", isin, instrument.session, instrument.security)
      catchUp(catching.copy(security = Some(instrument.security)))

    case Event(CatchedState(i, state), catching: CatchingData) if (i == isin) =>
      catchUp(catching.copy(state = Some(state)))

    case Event(CatchedParameters(i, params), catching: CatchingData) if (i == isin) =>
      catchUp(catching.copy(parameters = Some(params)))

    case Event(StateTimeout, _) => failed("Failed to catch instrument for isin = " + isin)
  }

  when(Ready) {
    case Event(Start, UnderlyingInstrument(security, _, _)) =>
      log.info("Start {} strategy", id)
      tradesData ! SubscribeTrades(priceRegression, security)
      goto(WarmingUp)
  }


  when(WarmingUp) {
    case Event(slope: PriceSlope, _) if (initialSlope.isEmpty) =>
      initialSlope = Some(slope)
      stay()

    case Event(PriceSlope(time, price, _, _), _) if (initialSlope.isDefined && !warmedUp(time)) =>
      stay()

    case Event(PriceSlope(time, price, _, _), _) if (initialSlope.isDefined && warmedUp(time)) =>
      log.info("Warmed up, start trading!")
      goto(Parity)
  }

  when(Parity) {
    case Event(slope: PriceSlope, _) =>
      log.info("Slope = " + slope)
      stay()
  }

  whenUnhandled {
    case Event(CatchedState(i, state), instrument: UnderlyingInstrument) if (i == isin) =>
      stay() using instrument.copy(state = state)

    case Event(CatchedParameters(i, params), instrument: UnderlyingInstrument) if (i == isin) =>
      stay() using instrument.copy(parameters = params)
  }

  onTransition {
    case CatchingInstrument -> Ready => engine.reportReady(Map())
  }

  initialize

  private def warmedUp(time: DateTime) = initialSlope.get.time.getMillis < time.getMillis - math.max(primaryDuration.toMillis, primaryDuration.toMillis)

  private def catchUp(catching: CatchingData) = catching match {
    case CatchingData(Some(security), Some(parameters), Some(state)) =>
      val underlying = UnderlyingInstrument(security, parameters, state)
      log.debug("Ready to trade using unserlying instrument = " + underlying)
      goto(Ready) using underlying

    case _ => stay() using catching
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

  val SecondaryScale = 10000

  case object Computing

  implicit def pimpRawData(data: Seq[RawData]) = new {
    def normalized: Seq[NormalizedData] = {
      import scalaz.Scalaz._
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