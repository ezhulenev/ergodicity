package com.ergodicity.core.order

import akka.event.Logging
import com.ergodicity.core.common._
import com.ergodicity.core.order.FutureOrders.BindFutTradeRepl
import akka.actor._
import akka.actor.FSM.Failure
import com.ergodicity.cgate.scheme.FutTrade
import com.ergodicity.cgate.DataStream._
import com.ergodicity.cgate.Protocol.ReadsFutTradeOrders
import com.ergodicity.cgate.StreamEvent._
import com.ergodicity.cgate.Reads

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

  val read = implicitly[Reads[FutTrade.orders_log]]

  startWith(Idle, Map())

  when(Idle) {
    case Event(BindFutTradeRepl(dataStream), _) =>
      dataStream ! BindTable(FutTrade.orders_log.TABLE_INDEX, self)
      goto(Binded)
  }

  when(Binded) {
    handleDataStreamEvents orElse trackSession orElse dropSession
  }

  private def handleDataStreamEvents: StateFunction = {
    case Event(TnBegin, _) => stay()

    case Event(TnCommit, _) => stay()

    case Event(e@ClearDeleted(FutTrade.orders_log.TABLE_INDEX, replRev), _) => stay()

    case Event(e@StreamData(FutTrade.orders_log.TABLE_INDEX, data), _) if (read(data).get_replAct() != 0) => stop(Failure("Illegal event: " + e))

    case Event(StreamData(FutTrade.orders_log.TABLE_INDEX, data), _) if (stateData.contains(read(data).get_sess_id())) =>
      val record = read(data)
      stateData(record.get_sess_id()) ! record
      stay()

    case Event(StreamData(FutTrade.orders_log.TABLE_INDEX, data), sessions) if (!stateData.contains(read(data).get_sess_id())) =>
      val record = read(data)
      val sessionId = record.get_sess_id()
      log.debug("Create session orders for: " + sessionId)
      val actor = context.actorOf(Props(new FutureSessionOrders(sessionId)), "Session-" + sessionId)
      actor ! record
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
    case record: FutTrade.orders_log if (Action(record) == Create && !orderExists(record) && matchSession(record)) =>
      val orderId = record.get_id_ord()
      log.debug("Create new order, id = " + orderId)
      val order = context.actorOf(Props(new Order(record)), "Order-" + orderId)
      orders = orders + (orderId -> order)
  }

  private def handleDeleteOrder: Receive = {
    case record: FutTrade.orders_log if (Action(record) == Delete && orderExists(record) && matchSession(record)) =>
      val orderId = record.get_id_ord()
      log.debug("Cancel order, id = " + orderId)
      orders(orderId) ! CancelOrder(record.amount)
  }

  private def handleFillOrder: Receive = {
    case record: FutTrade.orders_log if (Action(record) == Fill && orderExists(record) && matchSession(record)) =>
      val orderId = record.get_id_ord()
      log.debug("Fill order, id = " + orderId + ", amount = " + record.amount + ", rest = " + record.get_amount_rest() + ", deal id = " + record.get_id_deal())
      orders(orderId) ! FillOrder(record.price, record.amount)
  }

  private def matchSession(record: FutTrade.orders_log) = record.get_sess_id() == sessionId

  private def orderExists(record: FutTrade.orders_log) = orders.contains(record.get_id_ord())
}