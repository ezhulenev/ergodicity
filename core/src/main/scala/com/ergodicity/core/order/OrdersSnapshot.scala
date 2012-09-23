package com.ergodicity.core.order

import akka.actor.FSM.{Failure, Transition, SubscribeTransitionCallBack}
import akka.actor.{LoggingFSM, Actor, ActorRef}
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.Protocol._
import com.ergodicity.cgate.StreamEvent.{ClearDeleted, TnCommit, TnBegin, StreamData}
import com.ergodicity.cgate.scheme.OrdBook
import com.ergodicity.cgate.{DataStreamState, Reads}
import com.ergodicity.core.order.OrdersSnapshotActor.{OrdersSnapshot, GetOrdersSnapshot}
import com.ergodicity.core.order.SnapshotState.{Loaded, Loading}
import org.joda.time.DateTime
import com.ergodicity.core.IsinId

sealed trait SnapshotState

object SnapshotState {

  case object Loading extends SnapshotState

  case object Loaded extends SnapshotState

}

object OrdersSnapshotActor {

  case object GetOrdersSnapshot

  case class OrdersSnapshot(revision: Long, moment: DateTime, orders: Seq[(Order, Seq[Fill])])

}

class OrdersSnapshotActor(OrderBookStream: ActorRef) extends Actor with LoggingFSM[SnapshotState, (Option[Long], Option[DateTime], Map[Long, (Order, Seq[Fill])])] {

  override def preStart() {
    OrderBookStream ! SubscribeStreamEvents(self)
    OrderBookStream ! SubscribeTransitionCallBack(self)
  }

  var pending: Seq[ActorRef] = Seq()

  startWith(Loading, (None, None, Map()))

  private def toOrder(record: OrdBook.orders) = Order(record.get_id_ord(),
    record.get_sess_id(),
    IsinId(record.get_isin_id()),
    mapOrderType(record.get_status()),
    mapOrderDirection(record.get_dir()),
    record.get_price(),
    record.get_init_amount()
  )

  private def toAction(record: OrdBook.orders): Seq[Fill] = record.get_action() match {
    case 1 => Nil
    case 2 if (record.get_amount_rest() == record.get_init_amount() - record.get_amount()) =>
      Fill(record.get_amount(), record.get_amount_rest(), Some(record.get_id_deal(), record.get_deal_price())) :: Nil
    case 2 =>
      val amount = record.get_init_amount() - record.get_amount_rest() - record.get_amount()
      val rest = record.get_amount_rest() + record.get_amount()
      val anonymousFill = Fill(amount, rest, None)
      val fill = Fill(record.get_amount(), record.get_amount_rest(), Some(record.get_id_deal(), record.get_deal_price()))
      anonymousFill :: fill :: Nil
    case err => throw new IllegalArgumentException("Illegal order action = " + err)
  }

  when(Loading) {
    case Event(StreamData(OrdBook.orders.TABLE_INDEX, data), (rev, moment, orders)) =>
      val record = implicitly[Reads[OrdBook.orders]] apply data

      val replId = record.get_replID()

      if (record.get_replAct() == replId) {
        stay() using(rev, moment, orders - replId)
      } else {
        val order = toOrder(record)
        val action = toAction(record)
        stay() using(rev, moment, orders + (replId ->(order, action)))
      }

    case Event(StreamData(OrdBook.info.TABLE_INDEX, data), (_, _, orders)) =>
      val record = implicitly[Reads[OrdBook.info]] apply data

      val revision = Some(record.get_logRev())
      val moment = Some(new DateTime(record.get_moment()))
      stay() using(revision, moment, orders)

    case Event(Transition(OrderBookStream, DataStreamState.Opened, DataStreamState.Closed), (Some(rev), Some(moment), orders)) =>
      pending foreach (_ ! OrdersSnapshot(rev, moment, orders.values.toList))
      goto(Loaded)

    case Event(GetOrdersSnapshot, _) =>
      pending = pending :+ sender
      stay()
  }

  when(Loaded) {
    case Event(GetOrdersSnapshot, (Some(rev), Some(moment), orders)) =>
      sender ! OrdersSnapshot(rev, moment, orders.values.toList)
      stay()

    case Event(GetOrdersSnapshot, state) => stop(Failure("Illegal state = " + state))
  }

  whenUnhandled {
    case Event(TnBegin, _) => stay()
    case Event(TnCommit, _) => stay()
    case Event(_: ClearDeleted, _) => stay()
  }

  initialize
}