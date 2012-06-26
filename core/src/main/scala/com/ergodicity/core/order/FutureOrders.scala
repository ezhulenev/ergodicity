package com.ergodicity.core.order

import akka.event.Logging
import com.ergodicity.core.common.WhenUnhandled
import akka.actor.{Kill, Props, ActorRef, Actor}
import com.ergodicity.plaza2.DataStream._
import com.ergodicity.plaza2.scheme.common.OrderLogRecord

class FutureOrders extends Actor with WhenUnhandled {
  val log = Logging(context.system, self)

  protected[order] var sessions: Map[Int, ActorRef] = Map()

  protected def receive = trackSession orElse dropSession orElse handleDataStreamEvents orElse whenUnhandled

  private def handleDataStreamEvents: Receive = {
    case DataBegin =>

    case DataEnd =>

    case e@DatumDeleted("orders_log", replRev) => throw new OrdersTrackingException("Illegal event: " + e)

    case e@DataDeleted("orders_log", replId) => throw new OrdersTrackingException("Illegal event: " + e)

    case DataInserted("orders_log", record: OrderLogRecord) => sessionOrders(record.sess_id) ! record
  }

  private def trackSession: Receive = {
    case TrackSession(sessionId) =>
      log.info("Track session: " + sessionId)
      sender ! sessionOrders(sessionId)
  }

  private def dropSession: Receive = {
    case DropSession(sessionId) =>
      log.info("Drop session: " + sessionId)
      sessions.get(sessionId).map(_ ! Kill)
      sessions = sessions - sessionId
  }

  def sessionOrders(sessionId: Int): ActorRef = {
    sessions.get(sessionId) getOrElse {
      log.debug("Create session orders for: " + sessionId)
      val actor = context.actorOf(Props(new FutureSessionOrders(sessionId)), "Session-" + sessionId)
      sessions = sessions + (sessionId -> actor)
      actor
    }
  }
}

class FutureSessionOrders(sessionId: Int) extends Actor with WhenUnhandled {
  val log = Logging(context.system, self)

  protected def receive = whenUnhandled
}