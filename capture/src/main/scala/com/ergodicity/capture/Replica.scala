package com.ergodicity.capture

import com.ergodicity.cgate.scheme._

case class Replica(replID: Long, replRev: Long, replAct: Long)

trait ReplicaExtractor[T] {
  def apply(in: T): Replica = repl(in)

  def repl(in: T): Replica
}

object ReplicaExtractor {
  type ReplicaRecord = {def get_replID(): Long; def get_replRev(): Long; def get_replAct(): Long}

  private def replica(in: ReplicaRecord) = Replica(in.get_replID(), in.get_replRev(), in.get_replAct())

  implicit val FutInfoSessionExtractor = new ReplicaExtractor[FutInfo.session] {
    def repl(in: FutInfo.session) = replica(in)
  }

  implicit val FutInfoSessionContentsExtractor = new ReplicaExtractor[FutInfo.fut_sess_contents] {
    def repl(in: FutInfo.fut_sess_contents) = replica(in)
  }

  implicit val FutInfoSysEventsExtractor = new ReplicaExtractor[FutInfo.sys_events] {
    def repl(in: FutInfo.sys_events) =
      replica(in)
  }

  implicit val OptInfoSessionContentsExtractor = new ReplicaExtractor[OptInfo.opt_sess_contents] {
    def repl(in: OptInfo.opt_sess_contents) = replica(in)
  }

  implicit val OptInfoSysEventsExtractor = new ReplicaExtractor[OptInfo.sys_events] {
    def repl(in: OptInfo.sys_events) =
      replica(in)
  }

  implicit val PosPositionsExtractor = new ReplicaExtractor[Pos.position] {
    def repl(in: Pos.position) = replica(in)
  }

  implicit val OrdLogOrdersExtractor = new ReplicaExtractor[OrdLog.orders_log] {
    def repl(in: OrdLog.orders_log) = replica(in)
  }

  implicit val FutTradeDealsExtractor = new ReplicaExtractor[FutTrade.deal] {
    def repl(in: FutTrade.deal) = replica(in)
  }

  implicit val FutTradeOrdersExtractor = new ReplicaExtractor[FutTrade.orders_log] {
    def repl(in: FutTrade.orders_log) = replica(in)
  }

  implicit val OptTradeDealsExtractor = new ReplicaExtractor[OptTrade.deal] {
    def repl(in: OptTrade.deal) = replica(in)
  }

  implicit val OptTradeOrdersExtractor = new ReplicaExtractor[OptTrade.orders_log] {
    def repl(in: OptTrade.orders_log) = replica(in)
  }

  implicit val OrderBookOrdersExtractor = new ReplicaExtractor[OrderBook.orders] {
    def repl(in: OrderBook.orders) = replica(in)
  }

  implicit val OrderBookInfoExtractor = new ReplicaExtractor[OrderBook.info] {
    def repl(in: OrderBook.info) = replica(in)
  }
}