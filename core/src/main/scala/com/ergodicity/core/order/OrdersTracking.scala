package com.ergodicity.core.order

import akka.actor._
import akka.util.duration._
import akka.pattern.ask
import com.ergodicity.cgate.DataStream._
import com.ergodicity.cgate.StreamEvent._
import com.ergodicity.cgate.scheme.{OptTrade, FutTrade}
import akka.dispatch.Await
import com.ergodicity.cgate.{WhenUnhandled, DataStreamState, Reads}
import collection.mutable
import akka.util
import com.ergodicity.cgate.DataStream.BindingSucceed
import akka.actor.FSM.Transition
import com.ergodicity.core.order.OrdersTrackingState.StreamStates
import akka.actor.FSM.CurrentState
import akka.actor.FSM.UnsubscribeTransitionCallBack
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.StreamEvent.ClearDeleted
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.cgate.DataStream.BindTable
import com.ergodicity.core.order
import order.OrdersTracking.{IllegalEvent, StickyAction}


private[order] sealed trait OrdersTrackingState

private[order] object OrdersTrackingState {

  case object Binded extends OrdersTrackingState

  case object Online extends OrdersTrackingState

  case class StreamStates(fut: Option[DataStreamState] = None, opt: Option[DataStreamState] = None)

}

object OrdersTracking {

  case class IllegalEvent(event: Any) extends IllegalArgumentException

  case class StickyAction(sessionId: Int, action: OrderAction)
}

class OrdersTracking(FutTradeStream: ActorRef, OptTradeStream: ActorRef) extends Actor with FSM[OrdersTrackingState, StreamStates] {

  import OrdersTracking._
  import OrdersTrackingState._

  implicit val timeout = util.Timeout(1.second)

  val sessions = mutable.Map[Int, ActorRef]()

  log.debug("Bind to FutTrade & OptTrade data streams")

  // Bind to tables
  val futuresDispatcher = context.actorOf(Props(new FutureOrdersDispatcher(self)), "FutureOrdersDispatcher")
  val optionsDispatcher = context.actorOf(Props(new OptionOrdersDispatcher(self)), "OptionOrdersDispatcher")
  val futuresBinding = (FutTradeStream ? BindTable(FutTrade.orders_log.TABLE_INDEX, futuresDispatcher)).mapTo[BindingResult]
  val optionsBinding = (OptTradeStream ? BindTable(OptTrade.orders_log.TABLE_INDEX, optionsDispatcher)).mapTo[BindingResult]

  Await.result(futuresBinding zip optionsBinding, 1.second) match {
    case (_: BindingSucceed, _: BindingSucceed) => log.info("Successfully binded to FutTrade & OptTrade streams")
    case failure => throw new IllegalStateException("Failed bind to FutTrade & OptTrade streams, failure = " + failure)
  }

  // Track Data Stream state
  FutTradeStream ! SubscribeTransitionCallBack(self)
  OptTradeStream ! SubscribeTransitionCallBack(self)

  startWith(Binded, StreamStates())

  when(Binded)(handleSessionEvents orElse trackSession orElse dropSession orElse {
    case Event(CurrentState(FutTradeStream, DataStreamState.Online), _) => goto(Online)
    case Event(Transition(FutTradeStream, _, DataStreamState.Online), _) => goto(Online)

  })

  when(Online) {
    handleSessionEvents orElse trackSession orElse dropSession
  }

  onTransition {
    case Binded -> Online =>
      FutTradeStream ! UnsubscribeTransitionCallBack(self)
      OptTradeStream ! UnsubscribeTransitionCallBack(self)
  }

  private def handleSessionEvents: StateFunction = {
    case Event(StickyAction(sessionId, action), _) =>
      lazy val actor = context.actorOf(Props(new SessionOrdersTracking(sessionId)), "Session-" + sessionId)
      sessions.getOrElseUpdate(sessionId, actor) ! action
      stay()
  }

  private def trackSession: StateFunction = {
    case Event(GetOrdersTracking(sessionId), _) =>
      lazy val actor = context.actorOf(Props(new SessionOrdersTracking(sessionId)), "Session-" + sessionId)
      sender ! sessions.getOrElseUpdate(sessionId, actor)
      stay()
  }

  private def dropSession: StateFunction = {
    case Event(DropSession(sessionId), _) if (sessions.contains(sessionId)) =>
      log.info("Drop session: " + sessionId)
      sessions(sessionId) ! Kill
      stay()
  }
}

class FutureOrdersDispatcher(ordersTracking: ActorRef)(implicit val read: Reads[FutTrade.orders_log]) extends Actor with ActorLogging with WhenUnhandled {
  protected def receive = handleDataStreamEvents orElse whenUnhandled

  private def handleDataStreamEvents: Receive = {
    case TnBegin =>

    case TnCommit =>

    case e@ClearDeleted(_, _) =>

    case e@StreamData(_, data) if (read(data).get_replAct() != 0) => throw new IllegalEvent(e)

    case StreamData(_, data) =>
      val record = read(data)
      ordersTracking ! StickyAction(record.get_sess_id(), com.ergodicity.core.order.OrderAction(record))
  }
}

class OptionOrdersDispatcher(ordersTracking: ActorRef)(implicit val read: Reads[OptTrade.orders_log]) extends Actor with ActorLogging with WhenUnhandled {
  protected def receive = handleDataStreamEvents orElse whenUnhandled

  private def handleDataStreamEvents: Receive = {
    case TnBegin =>

    case TnCommit =>

    case e@ClearDeleted(_, _) =>

    case e@StreamData(_, data) if (read(data).get_replAct() != 0) => throw new IllegalEvent(e)

    case StreamData(_, data) =>
      val record = read(data)
      ordersTracking ! StickyAction(record.get_sess_id(), com.ergodicity.core.order.OrderAction(record))
  }
}

class SessionOrdersTracking(sessionId: Int) extends Actor with ActorLogging with WhenUnhandled {
  protected[order] val orders = mutable.Map[Long, ActorRef]()

  protected def receive = handleCreateOrder orElse handleDeleteOrder orElse handleFillOrder orElse whenUnhandled

  private def handleCreateOrder: Receive = {
    case Create(props) =>
      val orderId = props.id
      log.debug("Create new order, id = " + orderId)
      val order = context.actorOf(Props(new OrderActor(props)), "Order-" + orderId)
      orders(orderId) = order
  }

  private def handleDeleteOrder: Receive = {
    case Delete(orderId, amount) =>
      log.debug("Cancel futOrder, id = " + orderId)
      orders(orderId) ! CancelOrder(amount)
  }

  private def handleFillOrder: Receive = {
    case Fill(orderId, deal, amount, price, rest) =>
      log.debug("Fill order, id = " + orderId + ", amount = " + amount + ", rest = " + rest + ", deal id = " + deal)
      orders(orderId) ! FillOrder(price, amount)
  }
}