package com.ergodicity.core.order

import akka.event.Logging
import com.ergodicity.plaza2.DataStream._
import com.ergodicity.plaza2.scheme.common.OrderLogRecord
import com.ergodicity.core.common._
import com.ergodicity.core.order.FutureOrders.BindFutTradeRepl
import com.ergodicity.plaza2.scheme.Deserializer
import akka.actor._
import akka.actor.FSM.Failure

object FutureOrders {

  case class BindFutTradeRepl(dataStream: ActorRef)

}

private[order] sealed trait FutureOrdersState

private[order] object FutureOrdersState {

  case object Idle extends FutureOrdersState

  case object Binded extends FutureOrdersState

}


class FutureOrders extends Actor with FSM[FutureOrdersState, Map[Int, ActorRef]] {

  import FutureOrdersState._

  startWith(Idle, Map())

  when(Idle) {
    case Event(BindFutTradeRepl(dataStream), _) =>
      dataStream ! BindTable("orders_log", self, implicitly[Deserializer[OrderLogRecord]])
      goto(Binded)
  }

  when(Binded) {
    handleDataStreamEvents orElse trackSession orElse dropSession
  }

  private def handleDataStreamEvents: StateFunction = {
    case Event(DataBegin, _) => stay()

    case Event(DataEnd, _) => stay()

    case Event(e@DatumDeleted("orders_log", replRev), _) => stop(Failure("Illegal event: " + e))

    case Event(e@DataDeleted("orders_log", replId), _) => stop(Failure("Illegal event: " + e))

    case Event(DataInserted("orders_log", record: OrderLogRecord), _) if (stateData.contains(record.sess_id)) =>
      stateData(record.sess_id) ! record
      stay()

    case Event(DataInserted("orders_log", record: OrderLogRecord), sessions) if (!stateData.contains(record.sess_id)) =>
      val sessionId = record.sess_id
      log.debug("Create session orders for: " + sessionId)
      val actor = context.actorOf(Props(new FutureSessionOrders(sessionId)), "Session-" + sessionId)
      stay() using (sessions + (sessionId -> actor))
  }

  private def trackSession: StateFunction = {
    case Event(TrackSession(sessionId), sessions) if (sessions.contains(sessionId)) =>
      sender ! sessions(sessionId)
      stay()

    case Event(TrackSession(sessionId), sessions) if (!sessions.contains(sessionId)) =>
      val actor = context.actorOf(Props(new FutureSessionOrders(sessionId)), "Session-" + sessionId)
      sender ! actor
      stay() using (sessions + (sessionId -> actor))
  }

  private def dropSession: StateFunction = {
    case Event(DropSession(sessionId), sessions) if (sessions.contains(sessionId)) =>
      log.info("Drop session: " + sessionId)
      sessions(sessionId) ! Kill
      stay() using (sessions - sessionId)
  }
}

class FutureSessionOrders(sessionId: Int) extends Actor with WhenUnhandled {
  val log = Logging(context.system, self)

  protected[order] var orders: Map[Long, ActorRef] = Map()

  protected def receive = handleCreateOrder orElse handleDeleteOrder orElse handleFillOrder orElse whenUnhandled

  private def handleCreateOrder: Receive = {
    case record: OrderLogRecord if (Action(record) == Create && !orderExists(record) && matchSession(record)) =>
      val orderId = record.id_ord
      log.debug("Create new order, id = " + orderId)
      val order = context.actorOf(Props(new Order(record)), "Order-" + orderId)
      orders = orders + (orderId -> order)
  }

  private def handleDeleteOrder: Receive = {
    case record: OrderLogRecord if (Action(record) == Delete && orderExists(record) && matchSession(record)) =>
      val orderId = record.id_ord
      log.debug("Cancel order, id = " + orderId)
      orders(orderId) ! CancelOrder(record.amount)
  }

  private def handleFillOrder: Receive = {
    case record: OrderLogRecord if (Action(record) == Fill && orderExists(record) && matchSession(record)) =>
      val orderId = record.id_ord
      log.debug("Fill order, id = " + orderId + ", amount = " + record.amount + ", rest = " + record.amount_rest + ", deal id = " + record.id_deal)
      orders(orderId) ! FillOrder(record.price, record.amount)
  }

  private def matchSession(record: OrderLogRecord) = record.sess_id == sessionId

  private def orderExists(record: OrderLogRecord) = orders.contains(record.id_ord)
}