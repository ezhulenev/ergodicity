package com.ergodicity.backtest

import akka.actor.{Props, ActorSystem}
import akka.dispatch.{Await, Future}
import akka.event.Logging
import akka.pattern.ask
import akka.util.duration._
import com.ergodicity.backtest.Backtest.BacktestReport
import com.ergodicity.backtest.Backtest.Config
import com.ergodicity.backtest.BacktestEngine.BacktestStubs
import com.ergodicity.backtest.BacktestEngine.GetStubs
import com.ergodicity.backtest.service.SessionContext
import com.ergodicity.backtest.service.SessionsService
import com.ergodicity.core._
import com.ergodicity.engine.Engine.StartEngine
import com.ergodicity.engine.strategy.StrategiesFactory
import com.ergodicity.schema.Session
import org.joda.time.Interval
import org.squeryl.PrimitiveTypeMode._
import com.ergodicity.backtest.service.SessionsService.SessionAssigned

object Backtest {

  case class Config(interval: Interval, securities: Seq[Isin])

  case class BacktestReport()

}

class Backtest(systemName: String, strategies: StrategiesFactory)(implicit config: Config, repository: MarketRepository) {
  val system = ActorSystem(systemName)

  implicit val timeout = akka.util.Timeout(1.second)
  implicit val executionContext = system.dispatcher

  val log = Logging(system, classOf[Backtest])

  val engine = system.actorOf(Props(new BacktestEngine(system) {
    def strategies = Backtest.this.strategies
  }))

  val stubs = Await.result((engine ? GetStubs).mapTo[BacktestStubs], 1.second)

  implicit val sessions = new SessionsService(stubs.futInfo, stubs.optInfo)

  def apply(): Future[BacktestReport] = {
    log.info("Execute backtesting:")
    log.info(" - interval = {}", config.interval)
    log.info(" - securities = {}", config.securities)
    log.info(" - strategies = {}", strategies.strategies.map(_.id))


    engine ! StartEngine

    val contents = inTransaction(repository.scan(config.interval).map {
      case (session, futures, options) => (session, futures.filter(c => config.securities.exists(_.isin == c.isin)), options.filter(c => config.securities.exists(_.isin == c.isin)))
    })

    if (contents.size < 1) {
      throw new IllegalArgumentException("No sessions available for given interval")
    }

    // Initialize first session to start engine
    val (s, f, o) = contents.head
    implicit val context = SessionContext(s, f, o)
    log.debug("Session: {}", s)
    log.debug("Contents: futures = {}, options = {}", f.size, o.size)

    val assigned = sessions.assign(s, f, o)

    engine ! StartEngine

    // processSession(assigned)

    // Process remaining sessions
    for ((session, futures, options) <- contents.tail) {
      implicit val context = SessionContext(session, futures, options)
      log.debug("Session: {}", session)
      log.debug("Contents: futures = {}, options = {}", futures.size, options.size)

      val assigned = sessions.assign(session, futures, options)
      processSession(assigned)
    }

    Future(BacktestReport())
  }

  private def processSession(assigned: SessionAssigned)(implicit context: SessionContext) {
    val eveningSession = assigned.start()

    val suspendedSession = eveningSession.suspend()

    val beforeIntradayClearing = suspendedSession.resume()

    val intradayClearing = beforeIntradayClearing.startIntradayClearing()

    val afterIntradayClearing = intradayClearing.stopIntradayClearing()

    val clearing = afterIntradayClearing.startClearing()

    val completed = clearing.complete()
  }
}