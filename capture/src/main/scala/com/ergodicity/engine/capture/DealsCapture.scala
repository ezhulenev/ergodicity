package com.ergodicity.engine.capture

import akka.actor.Actor
import scalax.io.{Resource, Seekable}
import com.ergodicity.engine.plaza2.DataStream._
import com.twitter.ostrich.stats.Stats
import akka.event.Logging
import com.ergodicity.engine.plaza2.scheme.{OptTrade, FutTrade}

case class DealsCaptureException(msg: String) extends RuntimeException(msg)

class DealsCapture extends Actor {
  val log = Logging(context.system, this)

  var count = 0;

  val someFile: Seekable = Resource.fromFile("C:\\Temp\\OrdersLog\\deals.txt")

  protected def receive = {
    case DataBegin => log.debug("Begin Deals data")
    case e@DatumDeleted(_, rev) => log.warning("Unexpected DatumDeleted event: " + e)
    case DataEnd => log.debug("End Deals data")
    case e@DataDeleted(_, replId) => throw DealsCaptureException("Unexpected DataDeleted event: " + e)

    case DataInserted(_, order: FutTrade.DealRecord) =>
      Stats.incr(self.path.name+"@FuturesDataInserted")
      count += 1
      if (count % 1000 == 0) {
        log.info("Inserted order#"+count+": " + order)
      }
      someFile.append(order.toString+"\r\n")

    case DataInserted(_, order: OptTrade.DealRecord) =>
      Stats.incr(self.path.name+"@OptionsDataInserted")
      count += 1
      if (count % 1000 == 0) {
        log.info("Inserted order#"+count+": " + order)
      }
      someFile.append(order.toString+"\r\n")

  }
}