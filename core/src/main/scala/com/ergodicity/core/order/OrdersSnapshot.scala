package com.ergodicity.core.order

import akka.actor.FSM.{Failure, Transition, SubscribeTransitionCallBack}
import akka.actor.{LoggingFSM, Actor, ActorRef}
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.Protocol._
import com.ergodicity.cgate.StreamEvent.StreamData
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

  case class OrdersSnapshot(revision: Long, moment: DateTime, orders: Seq[(Order, Option[Fill])])

}

class OrdersSnapshotActor(OrderBookStream: ActorRef) extends Actor with LoggingFSM[SnapshotState, (Option[Long], Option[DateTime], Seq[(Order, Option[Fill])])] {

  override def preStart() {
    OrderBookStream ! SubscribeStreamEvents(self)
    OrderBookStream ! SubscribeTransitionCallBack(self)
  }

  var pending: Seq[ActorRef] = Seq()

  startWith(Loading, (None, None, Seq()))

  private def toOrder(record: OrdBook.orders) = Order(record.get_id_ord(),
    record.get_sess_id(),
    IsinId(record.get_isin_id()),
    mapOrderType(record.get_status()),
    mapOrderDirection(record.get_dir()),
    record.get_price(),
    record.get_init_amount()
  )

  private def toAction(record: OrdBook.orders) = record.get_action() match {
    case 1 => None
    case 2 => Some(Fill(record.get_amount(), record.get_amount_rest(), Some(record.get_id_deal(), record.get_deal_price())))
    case err => throw new IllegalArgumentException("Illegal order action = " + err)
  }

  when(Loading) {
    case Event(StreamData(OrdBook.orders.TABLE_INDEX, data), (rev, moment, orders)) =>
      val record = implicitly[Reads[OrdBook.orders]] apply data

      val order = toOrder(record)
      val action = toAction(record)

      stay() using(rev, moment, orders :+(order, action))

    case Event(StreamData(OrdBook.info.TABLE_INDEX, data), (_, _, orders)) =>
      val record = implicitly[Reads[OrdBook.info]] apply data
      val revision = Some(record.get_logRev())
      val moment = Some(new DateTime(record.get_moment()))
      stay() using(revision, moment, orders)

    case Event(Transition(OrderBookStream, DataStreamState.Opened, DataStreamState.Closed), (Some(rev), Some(moment), orders)) =>
      pending foreach (_ ! OrdersSnapshot(rev, moment, orders))
      goto(Loaded)

    case Event(GetOrdersSnapshot, _) =>
      pending = pending :+ sender
      stay()
  }

  when(Loaded) {
    case Event(GetOrdersSnapshot, (Some(rev), Some(moment), orders)) =>
      sender ! OrdersSnapshot(rev, moment, orders)
      stay()

    case Event(GetOrdersSnapshot, state) => stop(Failure("Illegal state = " + state))
  }

  initialize
}