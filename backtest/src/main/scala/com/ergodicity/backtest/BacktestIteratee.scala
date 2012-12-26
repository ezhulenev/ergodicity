package com.ergodicity.backtest

import scalaz.{Input, IterV}
import scalaz.IterV.{EOF, Done, Cont}
import org.joda.time.Interval
import com.ergodicity.marketdb.model.{TradePayload, OrderPayload, MarketPayload}
import com.ergodicity.marketdb.TimeSeries

object BacktestIteratee {

  case class DispatcherReport(trades: Int, orders: Int) {
    def +(p: MarketPayload) = p match {
      case _: OrderPayload => copy(orders = orders + 1)
      case _: TradePayload => copy(trades = trades + 1)
    }
  }

  def dispatcher[P <: MarketPayload]: IterV[P, DispatcherReport] = {
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