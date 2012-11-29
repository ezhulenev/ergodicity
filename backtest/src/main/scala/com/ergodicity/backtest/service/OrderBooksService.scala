package com.ergodicity.backtest.service

import akka.actor.ActorRef
import com.ergodicity.backtest.cgate.ReplicationStreamListenerStubActor.DispatchData
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.scheme.{OrdBook, OrdLog}
import com.ergodicity.core.SessionId
import com.ergodicity.core.order.OrderBooksTracking.Snapshots
import com.ergodicity.core.order.OrdersSnapshotActor.OrdersSnapshot
import com.ergodicity.marketdb.model.OrderPayload
import com.ergodicity.backtest.service.OrdersService.{OptionOrder, FutureOrder}

object OrderBooksService {

  import OrdersService._

  implicit def convertOrderPayload(payload: OrderPayload) =
    OrderEvent(payload.orderId, payload.time, payload.status, payload.action, payload.dir, payload.price, payload.amount, payload.amount_rest, payload.deal)


  implicit def futureOrder2plaza(order: FutureOrder) = new {
    def asPlazaRecord = orderPayload2plaza(order.session.fut, order.id.id, order.event)
  }

  implicit def optionOrder2plaza(order: OptionOrder) = new {
    def asPlazaRecord = orderPayload2plaza(order.session.opt, order.id.id, order.event)
  }

  private[this] def orderPayload2plaza(sessionId: Int, isinId: Int, order: OrderEvent): OrdLog.orders_log = {
    val Revision = 1

    val buff = allocate(Size.OrdLog)
    val cgate = new OrdLog.orders_log(buff)

    cgate.set_replRev(Revision)
    cgate.set_sess_id(sessionId)
    cgate.set_isin_id(isinId)

    import order._
    cgate.set_id_ord(orderId)
    cgate.set_moment(time.getMillis)
    cgate.set_status(status)
    cgate.set_action(action.toByte)
    cgate.set_dir(dir.toByte)
    cgate.set_price(price)
    cgate.set_amount(amount)
    cgate.set_amount_rest(amount_rest)
    deal.foreach {
      case (dealId, dealPrice) =>
        cgate.set_id_deal(dealId)
        cgate.set_deal_price(dealPrice)
    }
    cgate
  }

  implicit def ordersSnapshot2plaza(snapshot: OrdersSnapshot) = new {
    def asPlazaRecord = {
      assert(snapshot.orders.size == 0, "Can't dispatch non-empty snapshot")
      val buff = allocate(Size.OrdBook)
      val cgate = new OrdBook.info(buff)
      cgate.set_logRev(snapshot.revision)
      cgate.set_moment(snapshot.moment.getMillis)
      cgate
    }
  }
}

class OrderBooksService(ordLog: ActorRef, futOrderBook: ActorRef, optOrderBook: ActorRef)(implicit context: SessionContext) {

  import OrderBooksService._

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