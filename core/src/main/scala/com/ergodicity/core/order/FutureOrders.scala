package com.ergodicity.core.order

import akka.actor._
import akka.util.duration._
import akka.pattern.ask
import com.ergodicity.cgate.DataStream._
import com.ergodicity.cgate.Protocol.ReadsFutTradeOrders
import com.ergodicity.cgate.StreamEvent._
import com.ergodicity.cgate.scheme.FutTrade
import akka.dispatch.Await
import com.ergodicity.cgate.{WhenUnhandled, DataStreamState, Reads}
import akka.actor.FSM._
import akka.util.Timeout


private[order] sealed trait FutureOrdersState

private[order] object FutureOrdersState {

  case object Binded extends FutureOrdersState

  case object Online extends FutureOrdersState

}

object FutureOrders {
  case class IllegalEvent(event: Any) extends IllegalArgumentException
}

class FutureOrders(FutTradeStream: ActorRef) extends Actor with FSM[FutureOrdersState, Map[Int, ActorRef]] {

  import FutureOrders._
  import FutureOrdersState._

  implicit val timeout = Timeout(1.second)
  val read = implicitly[Reads[FutTrade.orders_log]]

  log.debug("Bind to FutTrade data stream")

  // Bind to tables
  val bindingResult = (FutTradeStream ? BindTable(FutTrade.orders_log.TABLE_INDEX, self)).mapTo[BindingResult]
  Await.result(bindingResult, 1.second) match {
    case BindingSucceed(_, _) =>
    case BindingFailed(_, _) => throw new IllegalStateException("FutTrade data stream in invalid state")
  }

  // Track Data Stream state
  FutTradeStream ! SubscribeTransitionCallBack(self)

  startWith(Binded, Map())

  when(Binded)(handleDataStreamEvents orElse trackSession orElse dropSession orElse {
    case Event(CurrentState(FutTradeStream, DataStreamState.Online), _) => goto(Online)
    case Event(Transition(FutTradeStream, _, DataStreamState.Online), _) => goto(Online)

  })

  when(Online) {
    handleDataStreamEvents orElse trackSession orElse dropSession
  }

  onTransition {
    case Binded -> Online => FutTradeStream ! UnsubscribeTransitionCallBack(self)
  }

  private def handleDataStreamEvents: StateFunction = {
    case Event(TnBegin, _) => stay()

    case Event(TnCommit, _) => stay()

    case Event(e@ClearDeleted(FutTrade.orders_log.TABLE_INDEX, replRev), _) => stay()

    case Event(e@StreamData(FutTrade.orders_log.TABLE_INDEX, data), _) if (read(data).get_replAct() != 0) => throw new IllegalEvent(e)

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

class FutureSessionOrders(sessionId: Int) extends Actor with ActorLogging with WhenUnhandled {
  protected[order] var orders: Map[Long, ActorRef] = Map()

  protected def receive = handleCreateOrder orElse handleDeleteOrder orElse handleFillOrder orElse whenUnhandled

  private def handleCreateOrder: Receive = {
    case record: FutTrade.orders_log if (Action(record) == Create && !orderExists(record) && matchSession(record)) =>
      val orderId = record.get_id_ord()
      log.debug("Create new futOrder, id = " + orderId)
      val order = context.actorOf(Props(new Order(record)), "Order-" + orderId)
      orders = orders + (orderId -> order)
  }

  private def handleDeleteOrder: Receive = {
    case record: FutTrade.orders_log if (Action(record) == Delete && orderExists(record) && matchSession(record)) =>
      val orderId = record.get_id_ord()
      log.debug("Cancel futOrder, id = " + orderId)
      orders(orderId) ! CancelOrder(record.amount)
  }

  private def handleFillOrder: Receive = {
    case record: FutTrade.orders_log if (Action(record) == Fill && orderExists(record) && matchSession(record)) =>
      val orderId = record.get_id_ord()
      log.debug("Fill futOrder, id = " + orderId + ", amount = " + record.amount + ", rest = " + record.get_amount_rest() + ", deal id = " + record.get_id_deal())
      orders(orderId) ! FillOrder(record.price, record.amount)
  }

  private def matchSession(record: FutTrade.orders_log) = record.get_sess_id() == sessionId

  private def orderExists(record: FutTrade.orders_log) = orders.contains(record.get_id_ord())
}