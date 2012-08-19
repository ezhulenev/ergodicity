package com.ergodicity.cgate.repository

import com.ergodicity.cgate.scheme.FutInfo.{sys_events, fut_sess_contents, session}
import com.ergodicity.cgate.scheme.OptInfo.opt_sess_contents
import com.ergodicity.cgate.scheme.Pos.position
import com.ergodicity.cgate.scheme._
import com.ergodicity.cgate.scheme.FutTrade.deal
import com.ergodicity.cgate.scheme.OrderBook.{info, orders}

case class Replica(replID: Long, replRev: Long, replAct: Long)

trait ReplicaExtractor[T] {
  def apply(in: T): Replica = repl(in)

  def repl(in: T): Replica
}

object ReplicaExtractor {
  type ReplicaRecord = {def get_replID(): Long; def get_replRev(): Long; def get_replAct(): Long}

  private def replica(in: ReplicaRecord) = Replica(in.get_replID(), in.get_replRev(), in.get_replAct())

  implicit val FutInfoSessionExtractor = new ReplicaExtractor[FutInfo.session] {
    def repl(in: session) = replica(in)
  }

  implicit val FutInfoSessionContentsExtractor = new ReplicaExtractor[FutInfo.fut_sess_contents] {
    def repl(in: fut_sess_contents) = replica(in)
  }

  implicit val FutInfoSysEventsExtractor = new ReplicaExtractor[FutInfo.sys_events] {
    def repl(in: sys_events) =
      replica(in)
  }

  implicit val OptInfoSessionContentsExtractor = new ReplicaExtractor[OptInfo.opt_sess_contents] {
    def repl(in: opt_sess_contents) = replica(in)
  }

  implicit val PosPositionsExtractor = new ReplicaExtractor[Pos.position] {
    def repl(in: position) = replica(in)
  }

  implicit val OrdLogOrdersExtractor = new ReplicaExtractor[OrdLog.orders_log] {
    def repl(in: OrdLog.orders_log) = replica(in)
  }

  implicit val FutTradeDealsExtractor = new ReplicaExtractor[FutTrade.deal] {
    def repl(in: deal) = replica(in)
  }

  implicit val FutTradeOrdersExtractor = new ReplicaExtractor[FutTrade.orders_log] {
    def repl(in: FutTrade.orders_log) = replica(in)
  }

  implicit val OrderBookOrdersExtractor = new ReplicaExtractor[OrderBook.orders] {
    def repl(in: orders) = replica(in)
  }

  implicit val OrderBookInfoExtractor = new ReplicaExtractor[OrderBook.info] {
    def repl(in: info) = replica(in)
  }
}

