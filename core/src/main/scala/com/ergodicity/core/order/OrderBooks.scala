package com.ergodicity.core.order

import akka.actor.{FSM, ActorLogging, ActorRef, Actor}
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.scheme.OrdLog
import com.ergodicity.cgate.Protocol._
import com.ergodicity.cgate.{Reads, WhenUnhandled}
import com.ergodicity.core.order.OrderBooks.{OrderBookAction,  StickyAction}
import com.ergodicity.core.order.OrderBooksState.WaitingSnapshots
import com.ergodicity.core.session.SessionActor.AssignedContents
import com.ergodicity.core.{IsinId, SessionId}
import com.ergodicity.core.order.OrderBookSnapshot.OrdersSnapshot

object OrderBooks {

  case class Snapshots(fut: OrdersSnapshot, opt: OrdersSnapshot)

  case class DropSession(sessionId: Int)

  case class StickyAction(isin: IsinId, action: Action)

  case class OrderBookAction(revision: Long, sessionId: Int, action: StickyAction)

}

sealed trait OrderBooksState

object OrderBooksState {

  case object WaitingSnapshots extends OrderBooksState

  case object Opening extends OrderBooksState

  case object Online extends OrderBooksState

}

class OrderBooks(OrdLogStream: ActorRef) extends Actor with FSM[OrderBooksState, AssignedContents] {

  startWith(WaitingSnapshots, AssignedContents(Set()))

  whenUnhandled {
    case Event(AssignedContents(contents), AssignedContents(old)) =>
      stay() using AssignedContents(contents ++ old)
  }

  initialize
}

class SessionOrderBooks(session: SessionId) extends Actor {
  protected def receive = null
}

class OrderBooksDispatcher(orderBooks: ActorRef, OrdLogStream: ActorRef) extends Actor with ActorLogging with WhenUnhandled {

  override def preStart() {
    OrdLogStream ! SubscribeStreamEvents(self)
  }

  protected def receive = receiveOrders orElse whenUnhandled

  private def receiveOrders: Receive = {
    case StreamData(OrdLog.orders_log.TABLE_INDEX, data) =>
      val record = implicitly[Reads[OrdLog.orders_log]] apply data
      val revision = record.get_replRev()
      val session = record.get_sess_id()
      val isin = IsinId(record.get_isin_id())
      val action = Action(record)
      orderBooks ! OrderBookAction(revision, session, StickyAction(isin, action))
  }
}