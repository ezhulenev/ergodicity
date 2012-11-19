package com.ergodicity.backtest.service

import akka.actor.{ActorSystem, ActorRef}
import akka.event.Logging
import com.ergodicity.schema.{OptSessContents, FutSessContents, Session}
import com.ergodicity.core.SessionId
import com.ergodicity.core.session.{InstrumentState, IntradayClearingState, SessionState}
import java.util.concurrent.atomic.AtomicInteger
import com.ergodicity.backtest.cgate.ListenerStubActor.Dispatch
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}
import com.ergodicity.cgate.SysEvent.SessionDataReady
import com.ergodicity.core.SessionsTracking.{OptSysEvent, FutSysEvent}

class SessionsService(futInfo: ActorRef, optInfo: ActorRef)(implicit system: ActorSystem) {
  val log = Logging(system, classOf[SessionsService])

  val sysEventsCounter = new AtomicInteger(0)

  def assign(session: Session, futures: Seq[FutSessContents], options: Seq[OptSessContents]): SessionManager = {
    val id = SessionId(session.sess_id, session.opt_sess_id)

    val sessionRecord = session.asPlazaRecord
    val futuresContents = futures map (_.asPlazaRecord)
    val optionsContents = options map (_.asPlazaRecord)

    // -- Update state to Assigned
    sessionRecord.set_state(SessionState.Assigned.toInt)
    sessionRecord.set_inter_cl_state(IntradayClearingState.Oncoming.toInt)
    futuresContents foreach (_.set_state(InstrumentState.Assigned.toInt))

    // -- Dispatch Session details and contents
    futInfo ! Dispatch(StreamData(FutInfo.session.TABLE_INDEX, sessionRecord.getData) :: Nil)
    futInfo ! Dispatch(futuresContents.map(rec => StreamData(FutInfo.fut_sess_contents.TABLE_INDEX, rec.getData)))
    optInfo ! Dispatch(optionsContents.map(rec => StreamData(OptInfo.opt_sess_contents.TABLE_INDEX, rec.getData)))

    // -- Dispatch sys events
    val eventId = sysEventsCounter.incrementAndGet()
    val futuresDataReady = FutSysEvent apply SessionDataReady(eventId, session.sess_id)
    val optionsDataReady = OptSysEvent apply SessionDataReady(eventId, session.opt_sess_id)

    futInfo ! Dispatch(StreamData(FutInfo.sys_events.TABLE_INDEX, futuresDataReady.asPlazaRecord.getData) :: Nil)
    optInfo ! Dispatch(StreamData(OptInfo.sys_events.TABLE_INDEX, optionsDataReady.asPlazaRecord.getData) :: Nil)

    // -- Construct manager for particular session
    new SessionManager(id)
  }
}

class SessionManager(val id: SessionId) {

}