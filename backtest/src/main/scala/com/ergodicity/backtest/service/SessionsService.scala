package com.ergodicity.backtest.service

import akka.actor.{ActorSystem, ActorRef}
import com.ergodicity.backtest.cgate.ListenerStubActor.Dispatch
import com.ergodicity.backtest.service.SessionsService.SessionAssigned
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.SysEvent.SessionDataReady
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}
import com.ergodicity.core.SessionId
import com.ergodicity.core.SessionsTracking.{OptSysEvent, FutSysEvent}
import com.ergodicity.core.session.{InstrumentState, IntradayClearingState, SessionState}
import com.ergodicity.schema.{OptSessContents, FutSessContents, Session}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable

object SessionsService {

  sealed trait Session {
    def id: SessionId
  }

  trait Cancel {
    self: Session =>

    protected def service: SessionsService

    def cancel() = new SessionCancelled(service.update(id, SessionState.Canceled, IntradayClearingState.Undefined))(service)
  }

  class SessionAssigned(val id: SessionId)(implicit protected val service: SessionsService) extends Session with Cancel {

    import service._

    def start(): EveningSession = new EveningSession(update(id, SessionState.Online, IntradayClearingState.Oncoming))
  }

  class EveningSession(val id: SessionId)(implicit protected val service: SessionsService) extends Session with Cancel {

    import service._

    def suspend() = new SessionSuspended(update(id, SessionState.Suspended, IntradayClearingState.Oncoming))
  }

  class SessionSuspended(val id: SessionId)(implicit protected val service: SessionsService) extends Session with Cancel {

    import service._

    def resume() = new SessionBeforeIntClearing(update(id, SessionState.Online, IntradayClearingState.Oncoming))
  }

  class SessionBeforeIntClearing(val id: SessionId)(implicit protected val service: SessionsService) extends Session with Cancel {

    import service._

    def startIntradayClearing() = new SessionIntradayClearing(update(id, SessionState.Suspended, IntradayClearingState.Running))
  }

  class SessionIntradayClearing(val id: SessionId)(implicit protected val service: SessionsService) extends Session {

    import service._

    def stopIntradayClearing() = new SessionAfterIntClearing(update(id, SessionState.Online, IntradayClearingState.Completed))
  }

  class SessionAfterIntClearing(val id: SessionId)(implicit protected val service: SessionsService) extends Session with Cancel {

    import service._

    def startClearing() = new SessionClearing(update(id, SessionState.Suspended, IntradayClearingState.Completed))
  }

  class SessionClearing(val id: SessionId)(implicit protected val service: SessionsService) extends Session {

    import service._

    def complete() = new SessionCompleted(update(id, SessionState.Completed, IntradayClearingState.Completed))
  }

  class SessionCompleted(val id: SessionId)(implicit protected val service: SessionsService) extends Session

  class SessionCancelled(val id: SessionId)(implicit protected val service: SessionsService) extends Session

}

class SessionsService(futInfo: ActorRef, optInfo: ActorRef)(implicit system: ActorSystem) {
  private[this] implicit val Service: SessionsService = this

  private[this] val sysEventsCounter = new AtomicInteger(0)

  private[this] val sessions = mutable.Map[SessionId, (Session, Seq[FutSessContents], Seq[OptSessContents])]()

  def assign(session: Session, futures: Seq[FutSessContents], options: Seq[OptSessContents]): SessionAssigned = {
    val id = dispatch(session, futures, options) using(SessionState.Assigned, IntradayClearingState.Oncoming)
    sessions(id) = (session, futures, options)
    sessionDataReady(id)
    new SessionAssigned(id)
  }

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
      futInfo ! Dispatch(futuresContents.map(rec => StreamData(FutInfo.fut_sess_contents.TABLE_INDEX, rec.getData)))
      optInfo ! Dispatch(optionsContents.map(rec => StreamData(OptInfo.opt_sess_contents.TABLE_INDEX, rec.getData)))
      futInfo ! Dispatch(StreamData(FutInfo.session.TABLE_INDEX, sessionRecord.getData) :: Nil)

      id
    }
  }

  private[this] def sessionDataReady(id: SessionId) {
    val eventId = sysEventsCounter.incrementAndGet()
    val futuresDataReady = FutSysEvent apply SessionDataReady(eventId, id.fut)
    val optionsDataReady = OptSysEvent apply SessionDataReady(eventId, id.opt)

    futInfo ! Dispatch(StreamData(FutInfo.sys_events.TABLE_INDEX, futuresDataReady.asPlazaRecord.getData) :: Nil)
    optInfo ! Dispatch(StreamData(OptInfo.sys_events.TABLE_INDEX, optionsDataReady.asPlazaRecord.getData) :: Nil)
  }
}