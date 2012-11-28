package com.ergodicity.backtest

import com.ergodicity.backtest.service.OrderBooksService.{OptionOrder, FutureOrder}
import com.ergodicity.backtest.service.TradesService.FutureTrade
import com.ergodicity.backtest.service.TradesService.OptionTrade
import com.ergodicity.cgate.SysEvent.IntradayClearingFinished
import com.ergodicity.cgate.SysEvent.SessionDataReady
import com.ergodicity.cgate.SysEvent.UnknownEvent
import com.ergodicity.cgate.scheme._
import com.ergodicity.core.SessionsTracking.FutSysEvent
import com.ergodicity.core.SessionsTracking.OptSysEvent
import com.ergodicity.marketdb.model.OrderPayload
import com.ergodicity.schema.{OptSessContents, FutSessContents, Session}
import java.math
import java.nio.{ByteOrder, ByteBuffer}
import service.PositionsService.ManagedPosition
import com.ergodicity.core.order.OrdersSnapshotActor.OrdersSnapshot

package object service {

  object Size {
    val Session = 144
    val Future = 396
    val Option = 366
    val SysEvent = 105
    val Pos = 92
    val FutTrade = 282
    val OptTrade = 270
    val OrdLog = 100
    val OrdBook = 50
  }

  implicit def toJbd(v: BigDecimal): java.math.BigDecimal = new math.BigDecimal(v.toString())

  private def allocate(size: Int) = {
    val buff = ByteBuffer.allocate(size)
    buff.order(ByteOrder.nativeOrder())
    buff
  }

  implicit def session2plaza(obj: Session) = new {
    def asPlazaRecord = {
      val buff = allocate(Size.Session)

      val session = new FutInfo.session(buff)
      session.set_sess_id(obj.sess_id)
      session.set_begin(obj.begin)
      session.set_end(obj.end)
      session.set_opt_sess_id(obj.opt_sess_id)
      session.set_inter_cl_begin(obj.inter_cl_begin)
      session.set_inter_cl_end(obj.inter_cl_end)
      session.set_eve_on(obj.eve_on.toByte)
      session.set_eve_begin(obj.eve_begin)
      session.set_eve_end(obj.eve_end)
      session.set_mon_on(obj.mon_on.toByte)
      session.set_mon_begin(obj.mon_begin)
      session.set_mon_end(obj.mon_end)
      session.set_pos_transfer_begin(obj.pos_transfer_begin)
      session.set_pos_transfer_end(obj.pos_transfer_end)
      session
    }
  }

  implicit def future2plaza(obj: FutSessContents) = new {
    def asPlazaRecord = {
      val buff = allocate(Size.Future)

      val future = new FutInfo.fut_sess_contents(buff)
      future.set_sess_id(obj.sess_id)
      future.set_isin_id(obj.isin_id)
      future.set_short_isin(obj.short_isin)
      future.set_isin(obj.isin)
      future.set_name(obj.name)
      future.set_inst_term(obj.inst_term)
      future.set_code_vcb(obj.code_vcb)
      future.set_is_limited(obj.is_limited.toByte)
      future.set_limit_up(obj.limit_up)
      future.set_limit_down(obj.limit_down)
      future.set_old_kotir(obj.old_kotir)
      future.set_buy_deposit(obj.buy_deposit)
      future.set_sell_deposit(obj.sell_deposit)
      future.set_roundto(obj.roundto)
      future.set_min_step(obj.min_step)
      future.set_lot_volume(obj.lot_volume)
      future.set_step_price(obj.step_price)
      future.set_d_pg(obj.d_pg)
      future.set_is_spread(obj.is_spread.toByte)
      future.set_coeff(obj.coeff)
      future.set_d_exp(obj.d_exp)
      future.set_is_percent(obj.is_percent.toByte)
      future.set_percent_rate(obj.percent_rate)
      future.set_last_cl_quote(obj.last_cl_quote)
      future.set_signs(obj.signs)
      future.set_is_trade_evening(obj.is_trade_evening.toByte)
      future.set_ticker(obj.ticker)
      future.set_price_dir(obj.price_dir.toByte)
      future.set_multileg_type(obj.multileg_type)
      future.set_legs_qty(obj.legs_qty)
      future.set_step_price_clr(obj.step_price_clr)
      future.set_step_price_interclr(obj.step_price_interclr)
      future.set_step_price_curr(obj.step_price_curr)
      future.set_d_start(obj.d_start)
      future
    }
  }

  implicit def option2plaza(obj: OptSessContents) = new {
    def asPlazaRecord = {
      val buff = allocate(Size.Option)

      val option = new OptInfo.opt_sess_contents(buff)
      option.set_sess_id(obj.sess_id)
      option.set_isin_id(obj.isin_id)
      option.set_isin(obj.isin)
      option.set_short_isin(obj.short_isin)
      option.set_name(obj.name)
      option.set_code_vcb(obj.code_vcb)
      option.set_fut_isin_id(obj.fut_isin_id)
      option.set_is_limited(obj.is_limited.toByte)
      option.set_limit_up(obj.limit_up)
      option.set_limit_down(obj.limit_down)
      option.set_old_kotir(obj.old_kotir)
      option.set_bgo_c(obj.bgo_c)
      option.set_bgo_nc(obj.bgo_nc)
      option.set_europe(obj.europe.toByte)
      option.set_put(obj.put.toByte)
      option.set_strike(obj.strike)
      option.set_roundto(obj.roundto)
      option.set_min_step(obj.min_step)
      option.set_lot_volume(obj.lot_volume)
      option.set_step_price(obj.step_price)
      option.set_d_pg(obj.d_pg)
      option.set_d_exec_beg(obj.d_exec_beg)
      option.set_d_exec_end(obj.d_exec_end)
      option.set_signs(obj.signs)
      option.set_last_cl_quote(obj.last_cl_quote)
      option.set_bgo_buy(obj.bgo_buy)
      option.set_base_isin_id(obj.base_isin_id)
      option.set_d_start(obj.d_start)
      option
    }
  }

  implicit def futSysEvent2plaza(event: FutSysEvent) = new {
    def asPlazaRecord = {
      val buff = allocate(Size.SysEvent)

      val sysEvent = new FutInfo.sys_events(buff)
      event.event match {
        case SessionDataReady(eventId, sessionId) =>
          sysEvent.set_event_type(1)
          sysEvent.set_event_id(eventId)
          sysEvent.set_sess_id(sessionId)

        case IntradayClearingFinished(eventId, sessionId) =>
          sysEvent.set_event_type(2)
          sysEvent.set_event_id(eventId)
          sysEvent.set_sess_id(sessionId)

        case UnknownEvent(eventId, sessionId, message) => throw new IllegalArgumentException("Illegal emulated system event")
      }
      sysEvent
    }
  }

  implicit def optSysEvent2plaza(event: OptSysEvent) = new {
    def asPlazaRecord = {
      val buff = allocate(Size.SysEvent)

      val sysEvent = new OptInfo.sys_events(buff)
      event.event match {
        case SessionDataReady(eventId, sessionId) =>
          sysEvent.set_event_type(1)
          sysEvent.set_event_id(eventId)
          sysEvent.set_sess_id(sessionId)

        case IntradayClearingFinished(eventId, sessionId) =>
          sysEvent.set_event_type(2)
          sysEvent.set_event_id(eventId)
          sysEvent.set_sess_id(sessionId)

        case UnknownEvent(eventId, sessionId, message) => throw new IllegalArgumentException("Illegal emulated system event")
      }
      sysEvent
    }
  }

  implicit def managedPosition2plaza(pos: ManagedPosition) = new {
    val (security, position, dynamics) = (pos.security, pos.position, pos.dynamics)

    def asPlazaRecord = {
      val buff = allocate(Size.Pos)

      val cgate = new Pos.position(buff)
      cgate.set_isin_id(security.id.id)
      cgate.set_pos(position.pos)
      cgate.set_open_qty(dynamics.open)
      cgate.set_buys_qty(dynamics.buys)
      cgate.set_sells_qty(dynamics.sells)
      cgate.set_net_volume_rur(dynamics.volume)
      dynamics.lastDealId.map(id => cgate.set_last_deal_id(id))

      cgate
    }
  }

  implicit def futureTrade2plaza(trade: FutureTrade) = new {

    import scalaz.Scalaz._

    def asPlazaRecord = {
      val buff = allocate(Size.FutTrade)
      val cgate = new FutTrade.deal(buff)

      cgate.set_sess_id(trade.session.fut)
      cgate.set_isin_id(trade.id.id)
      cgate.set_id_deal(trade.underlying.tradeId)
      cgate.set_price(trade.underlying.price)
      cgate.set_amount(trade.underlying.amount)
      cgate.set_moment(trade.underlying.time.getMillis)
      cgate.set_nosystem(trade.underlying.nosystem ? 1.toByte | 0.toByte)

      cgate
    }
  }

  implicit def optionTrade2plaza(trade: OptionTrade) = new {

    import scalaz.Scalaz._

    def asPlazaRecord = {
      val buff = allocate(Size.OptTrade)
      val cgate = new OptTrade.deal(buff)

      cgate.set_sess_id(trade.session.fut)
      cgate.set_isin_id(trade.id.id)
      cgate.set_id_deal(trade.underlying.tradeId)
      cgate.set_price(trade.underlying.price)
      cgate.set_amount(trade.underlying.amount)
      cgate.set_moment(trade.underlying.time.getMillis)
      cgate.set_nosystem(trade.underlying.nosystem ? 1.toByte | 0.toByte)

      cgate
    }
  }

  implicit def futureOrder2plaza(order: FutureOrder) = new {
    def asPlazaRecord = order2plaza(order.session.fut, order.id.id, order.underlying)
  }

  implicit def optionOrder2plaza(order: OptionOrder) = new {
    def asPlazaRecord = order2plaza(order.session.opt, order.id.id, order.underlying)
  }

  private[this] def order2plaza(sessionId: Int, isinId: Int, order: OrderPayload): OrdLog.orders_log = {
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
