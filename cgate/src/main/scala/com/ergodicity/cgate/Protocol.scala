package com.ergodicity.cgate

import java.nio.ByteBuffer
import scheme._

trait Reads[T] {
  def apply(in: ByteBuffer): T = read(in)

  def read(in: ByteBuffer): T
}

object Protocol {

  implicit val ReadsFutInfoSessions = new Reads[FutInfo.session] {
    def read(in: ByteBuffer) = new FutInfo.session(in)
  }
  
  implicit val ReadsFutInfoSessionContents = new Reads[FutInfo.fut_sess_contents] {
    def read(in: ByteBuffer) = new FutInfo.fut_sess_contents(in)
  }

  implicit val ReadsOptInfoSessionContents = new Reads[OptInfo.opt_sess_contents] {
    def read(in: ByteBuffer) = new OptInfo.opt_sess_contents(in)
  }
  
  implicit val ReadsPosPositions = new Reads[Pos.position] {
    def read(in: ByteBuffer) = new Pos.position(in)
  }
  
  implicit val ReadsOrdLogOrders = new Reads[OrdLog.orders_log] {
    def read(in: ByteBuffer) = new OrdLog.orders_log(in)
  }
  
  implicit val ReadsFutTradeOrders = new Reads[FutTrade.orders_log] {
    def read(in: ByteBuffer) = new FutTrade.orders_log(in)
  }

  implicit val ReadsFutTradeDeals = new Reads[FutTrade.deal] {
    def read(in: ByteBuffer) = new FutTrade.deal(in)
  }

  implicit val ReadsOptTradeOrders = new Reads[OptTrade.orders_log] {
    def read(in: ByteBuffer) = new OptTrade.orders_log(in)
  }

  implicit val ReadsOptTradeDeals = new Reads[OptTrade.deal] {
    def read(in: ByteBuffer) = new OptTrade.deal(in)
  }

}