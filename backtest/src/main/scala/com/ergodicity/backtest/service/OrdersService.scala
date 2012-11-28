package com.ergodicity.backtest.service

import akka.actor.ActorRef
import com.ergodicity.backtest.cgate.DataStreamListenerStubActor.DispatchData
import com.ergodicity.backtest.service.OrdersService.{FutureOrder, OptionOrder}
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.scheme.{OrdBook, OrdLog}
import com.ergodicity.core.{IsinId, SessionId}
import com.ergodicity.marketdb.model.OrderPayload
import com.ergodicity.core.order.OrderBooksTracking.Snapshots

object OrdersService {

  sealed trait Order

  case class FutureOrder(session: SessionId, id: IsinId, underlying: OrderPayload)

  case class OptionOrder(session: SessionId, id: IsinId, underlying: OrderPayload)

}

class OrdersService(ordLog: ActorRef, futOrderBook: ActorRef, optOrderBook: ActorRef)(implicit context: SessionContext) {

  val sessionId = SessionId(context.session.sess_id, context.session.opt_sess_id)

  def dispatchSnapshots(snapshots: Snapshots) {
    futOrderBook ! DispatchData(StreamData(OrdBook.info.TABLE_INDEX, snapshots.fut.asPlazaRecord.getData) :: Nil)
    optOrderBook ! DispatchData(StreamData(OrdBook.info.TABLE_INDEX, snapshots.opt.asPlazaRecord.getData) :: Nil)
  }

  def dispatch(orders: OrderPayload*) {
    val futures = orders
      .filter(order => context.isFuture(order.security))
      .map(order => FutureOrder(sessionId, context.isinId(order.security).get, order))
      .map(_.asPlazaRecord)


    val options = orders
      .filter(order => context.isOption(order.security))
      .map(order => OptionOrder(sessionId, context.isinId(order.security).get, order))
      .map(_.asPlazaRecord)

    ordLog ! DispatchData((futures ++ options).map(r => StreamData(OrdLog.orders_log.TABLE_INDEX, r.getData)))
  }
}