package com.ergodicity.core.order

import akka.actor._
import collection.mutable
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.Protocol._
import com.ergodicity.cgate.StreamEvent.{TnCommit, TnBegin, StreamData}
import com.ergodicity.cgate.scheme.OrdLog
import com.ergodicity.cgate.{Reads, WhenUnhandled}
import com.ergodicity.core.FutureContract
import com.ergodicity.core.OptionContract
import com.ergodicity.core.order.OrderBooksTracking._
import com.ergodicity.core.order.OrdersSnapshotActor.OrdersSnapshot
import com.ergodicity.core.session.SessionActor.AssignedContents
import com.ergodicity.core.{Security, IsinId}
import com.ergodicity.core.order.OrderActor.IllegalOrderEvent
import com.ergodicity.core.order.OrderBooksTracking.IllegalLifeCycleEvent
import akka.actor.SupervisorStrategy.Stop

object OrderBooksTracking {

  case class Snapshots(fut: OrdersSnapshot, opt: OrdersSnapshot)

  case class DropSession(sessionId: Int)

  case class StickyAction(security: Security, action: OrderAction)

  case class OrderLog(revision: Long, sessionId: Int, isin: IsinId, action: OrderAction)

  class IllegalLifeCycleEvent(e: Any) extends RuntimeException("Illegal lifecycle event = " + e)

}

sealed trait OrderBooksState

object OrderBooksState {

  case object WaitingSnapshots extends OrderBooksState

  case object Synchronizing extends OrderBooksState

  case object Online extends OrderBooksState

}

sealed trait OrderBooksData

object OrderBooksData {

  case object Void extends OrderBooksData

  case class RevisionConstraints(futures: Long, options: Long) extends OrderBooksData {
    def max = scala.math.max(futures, options)
  }

}

class OrderBooksTracking(OrdLogStream: ActorRef) extends Actor with FSM[OrderBooksState, OrderBooksData] {

  import OrderBooksData._
  import OrderBooksState._

  val sessions = mutable.Map[Int, ActorRef]()
  var assigned = AssignedContents(Set())

  val dispatcher = context.actorOf(Props(new OrderLogDispatcher(self, OrdLogStream)), "OrderLogDispatcher")

  startWith(WaitingSnapshots, Void)

  when(WaitingSnapshots) {
    case Event(Snapshots(fut, opt), Void) =>
      fut.orders ++ opt.orders foreach {
        case (order, fill) if (order.noSystem == false) =>
          val sessionId = order.sessionId
          val isin = order.isin
          assigned ? isin map {
            case security =>
              dispatchSessionAction(sessionId, StickyAction(security, OrderAction(order.id, Create(order))))
              fill foreach (fill => dispatchSessionAction(sessionId, StickyAction(security, OrderAction(order.id, fill))))
          }
      }
      goto(Synchronizing) using RevisionConstraints(fut.revision, opt.revision)

    case Event(orderLog: OrderLog, _) => throw new IllegalLifeCycleEvent(orderLog)
  }

  when(Synchronizing) {
    case Event(OrderLog(revision, sessionId, isin, action), constraints@RevisionConstraints(fut, opt)) =>
      assigned ? isin map (security => {
        val accept = security match {
          case _: FutureContract => revision > fut
          case _: OptionContract => revision > opt
        }

        if (accept) {
          dispatchSessionAction(sessionId, StickyAction(security, action))
        }
      })

      if (revision > constraints.max) goto(Online) using Void else stay()
  }

  when(Online) {
    case Event(OrderLog(_, sessionId, isin, action), Void) =>
      assigned ? isin map (security => dispatchSessionAction(sessionId, StickyAction(security, action)))
      stay()
  }

  whenUnhandled {
    case Event(DropSession(id), _) =>
      log.info("Drop session, id = " + id)
      sessions.remove(id) foreach (_ ! PoisonPill)
      stay()

    case Event(AssignedContents(contents), _) =>
      log.info("Assigned contents; Size = " + contents.size)
      assigned = AssignedContents(contents ++ assigned.contents)
      stay()
  }

  initialize

  private def dispatchSessionAction(sessionId: Int, action: StickyAction) {
    lazy val actor = context.actorOf(Props(new SessionOrderBooksTracking(sessionId)), sessionId.toString)
    sessions.getOrElseUpdate(sessionId, actor) ! action
  }
}

class SessionOrderBooksTracking(sessionId: Int) extends Actor with ActorLogging with WhenUnhandled {
  val orderBooks = mutable.Map[Security, ActorRef]()


  override def supervisorStrategy() = OneForOneStrategy() {
    case e: IllegalOrderEvent => Stop
  }

  protected def receive = dispatchOrders orElse whenUnhandled

  private def dispatchOrders: Receive = {
    case StickyAction(security, action) =>
      lazy val orderBook = context.actorOf(Props(new OrderBookActor(security)), security.isin.toActorName)
      orderBooks.getOrElseUpdate(security, orderBook) ! action
  }
}

class OrderLogDispatcher(tracking: ActorRef, OrdLogStream: ActorRef) extends Actor with ActorLogging with WhenUnhandled {

  override def preStart() {
    OrdLogStream ! SubscribeStreamEvents(self)
  }

  protected def receive = receiveOrders orElse whenUnhandled

  private def receiveOrders: Receive = {
    case TnBegin =>
    case TnCommit =>

    case StreamData(OrdLog.orders_log.TABLE_INDEX, data) =>
      val record = implicitly[Reads[OrdLog.orders_log]] apply data
      val revision = record.get_replRev()
      val session = record.get_sess_id()
      val isin = IsinId(record.get_isin_id())
      val action = Action(record)
      val orderId = record.get_id_ord()
      tracking ! OrderLog(revision, session, isin, OrderAction(orderId, action))
  }
}