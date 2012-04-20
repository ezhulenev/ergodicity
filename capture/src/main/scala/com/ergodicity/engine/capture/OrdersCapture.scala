package com.ergodicity.engine.capture

import akka.actor.Actor
import akka.event.Logging
import com.ergodicity.engine.plaza2.DataStream._
import com.ergodicity.engine.plaza2.scheme.OrdLog
import scalax.io.{Seekable, Resource}
import com.twitter.ostrich.stats.Stats

case class OrdersCaptureException(msg: String) extends RuntimeException(msg)

class OrdersCapture extends Actor {
  val log = Logging(context.system, this)

  var count = 0;

  val someFile: Seekable = Resource.fromFile("C:\\Temp\\OrdersLog\\orders.txt")

  protected def receive = {
    case DataBegin => log.debug("Begin Orders data")
    case e@DatumDeleted(_, rev) => log.warning("Unexpected DatumDeleted event: " + e)
    case DataEnd => log.debug("End Orders data")
    case e@DataDeleted(_, replId) => throw OrdersCaptureException("Unexpected DataDeleted event: " + e)

    case DataInserted(_, order: OrdLog.OrdersLogRecord) =>
      Stats.incr(self.path.name+"@DataInserted")
      count += 1
      if (count % 1000 == 0) {
        log.info("Inserted order#"+count+": " + order)
      }
      someFile.append(order.toString+"\r\n")
  }
}