package com.ergodicity.backtest

import scalaz.{Input, IterV}
import scalaz.IterV.{EOF, Done, Cont}
import org.joda.time.Interval
import com.ergodicity.marketdb.model.{TradePayload, OrderPayload, MarketPayload}
import com.ergodicity.marketdb.TimeSeries
import com.ergodicity.backtest.service.{OrdersService, TradesService}

object BacktestIteratee {

  case class DispatcherReport(trades: Int, orders: Int) {
    def +(p: MarketPayload)(implicit ts: TradesService, os: OrdersService) = p match {
      case order: OrderPayload =>
        os.dispatch(order)
        copy(orders = orders + 1)

      case trade: TradePayload =>
        ts.dispatch(trade)
        copy(trades = trades + 1)
    }
  }

  def dispatcher[P <: MarketPayload](implicit trades: TradesService, orders: OrdersService): IterV[P, DispatcherReport] = {
    def step(report: DispatcherReport, p: P)(s: Input[P]): IterV[P, DispatcherReport] = {
      s(el = p2 => Cont(step(report + p2, p2)),
        empty = Cont(step(report + p, p)),
        eof = Done(report, EOF[P]))
    }

    def first(s: Input[P]): IterV[P, DispatcherReport] = {
      s(el = p1 => Cont(step(DispatcherReport(0, 0) + p1, p1)),
        empty = Cont(first),
        eof = Done(DispatcherReport(0, 0), EOF[P]))
    }

    Cont(first)
  }
}