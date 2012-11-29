package com.ergodicity.backtest.service

import akka.actor.ActorRef
import com.ergodicity.backtest.cgate.DataStreamListenerStubActor.DispatchData
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.SysEvent.{UnknownEvent, IntradayClearingFinished, SessionDataReady}
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}
import com.ergodicity.core.SessionId
import com.ergodicity.core.SessionsTracking.{OptSysEvent, FutSysEvent}
import com.ergodicity.core.session.{InstrumentState, IntradayClearingState, SessionState}
import com.ergodicity.schema.{Session, OptSessContents, FutSessContents}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable

object SessionsService {

  sealed trait ManagedSession {
    def id: SessionId
  }

  trait Cancel {
    self: ManagedSession =>

    def cancel()(implicit service: SessionsService) = SessionCancelled(service.update(id, SessionState.Canceled, IntradayClearingState.Undefined))
  }

  case class SessionAssigned(id: SessionId) extends ManagedSession with Cancel {
    def start()(implicit service: SessionsService): EveningSession = EveningSession(service.update(id, SessionState.Online, IntradayClearingState.Oncoming))
  }

  case class EveningSession(id: SessionId) extends ManagedSession with Cancel {
    def suspend()(implicit service: SessionsService) = SessionSuspended(service.update(id, SessionState.Suspended, IntradayClearingState.Oncoming))
  }

  case class SessionSuspended(id: SessionId) extends ManagedSession with Cancel {
    def resume()(implicit service: SessionsService) = SessionBeforeIntClearing(service.update(id, SessionState.Online, IntradayClearingState.Oncoming))
  }

  case class SessionBeforeIntClearing(id: SessionId) extends ManagedSession with Cancel {
    def startIntradayClearing()(implicit service: SessionsService) = SessionIntradayClearing(service.update(id, SessionState.Suspended, IntradayClearingState.Running))
  }

  case class SessionIntradayClearing(id: SessionId) extends ManagedSession {
    def stopIntradayClearing()(implicit service: SessionsService) = SessionAfterIntClearing(service.update(id, SessionState.Online, IntradayClearingState.Completed))
  }

  case class SessionAfterIntClearing(id: SessionId) extends ManagedSession with Cancel {
    def startClearing()(implicit service: SessionsService) = SessionClearing(service.update(id, SessionState.Suspended, IntradayClearingState.Completed))
  }

  case class SessionClearing(id: SessionId) extends ManagedSession {
    def complete()(implicit service: SessionsService) = SessionCompleted(service.update(id, SessionState.Completed, IntradayClearingState.Completed))
  }

  case class SessionCompleted(id: SessionId) extends ManagedSession

  case class SessionCancelled(id: SessionId) extends ManagedSession

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

}

class SessionsService(futInfo: ActorRef, optInfo: ActorRef) {
  private[this] implicit val Service: SessionsService = this

  private[this] val sysEventsCounter = new AtomicInteger(0)

  private[this] val sessions = mutable.Map[SessionId, (Session, Seq[FutSessContents], Seq[OptSessContents])]()

  import SessionsService._

  def assign(session: Session, futures: Seq[FutSessContents], options: Seq[OptSessContents]): SessionAssigned = {
    val id = dispatch(session, futures, options) using(SessionState.Assigned, IntradayClearingState.Oncoming)
    sessions(id) = (session, futures, options)
    sessionDataReady(id)
    SessionAssigned(id)
  }

  private[service] def contents(id: SessionId) = sessions(id)

  private[SessionsService] def remove(id: SessionId) {
    sessions.remove(id)
  }

  private[SessionsService] def update(id: SessionId, state: SessionState, intClearingState: IntradayClearingState) = {
    val session = sessions(id)
    dispatch(session._1, session._2, session._3) using(state, intClearingState)
  }

  private[this] def dispatch(session: Session, futures: Seq[FutSessContents], options: Seq[OptSessContents]) = new {
    def using(state: SessionState, intClearingState: IntradayClearingState): SessionId = {
      val id = SessionId(session.sess_id, session.opt_sess_id)

      val sessionRecord = session.asPlazaRecord
      val futuresContents = futures map (_.asPlazaRecord)
      val optionsContents = options map (_.asPlazaRecord)

      // -- Update session & instruments state
      sessionRecord.set_state(state.toInt)
      sessionRecord.set_inter_cl_state(intClearingState.toInt)
      futuresContents foreach (_.set_state(InstrumentState(state).toInt))

      // -- Dispatch Session details and contents
      futInfo ! DispatchData(futuresContents.map(rec => StreamData(FutInfo.fut_sess_contents.TABLE_INDEX, rec.getData)))
      optInfo ! DispatchData(optionsContents.map(rec => StreamData(OptInfo.opt_sess_contents.TABLE_INDEX, rec.getData)))
      futInfo ! DispatchData(StreamData(FutInfo.session.TABLE_INDEX, sessionRecord.getData) :: Nil)

      id
    }
  }

  private[this] def sessionDataReady(id: SessionId) {
    val eventId = sysEventsCounter.incrementAndGet()
    val futuresDataReady = FutSysEvent apply SessionDataReady(eventId, id.fut)
    val optionsDataReady = OptSysEvent apply SessionDataReady(eventId, id.opt)

    futInfo ! DispatchData(StreamData(FutInfo.sys_events.TABLE_INDEX, futuresDataReady.asPlazaRecord.getData) :: Nil)
    optInfo ! DispatchData(StreamData(OptInfo.sys_events.TABLE_INDEX, optionsDataReady.asPlazaRecord.getData) :: Nil)
  }
}