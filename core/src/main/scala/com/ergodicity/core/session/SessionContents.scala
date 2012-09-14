package com.ergodicity.core.session

import akka.actor.{ActorLogging, Props, ActorRef, Actor}
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.pattern.ask
import akka.util.duration._
import com.ergodicity.cgate.WhenUnhandled
import collection.mutable
import com.ergodicity.core.session.SessionActor.{AssignedInstruments, GetAssignedInstruments, GetInstrumentActor, GetState}
import akka.dispatch.Await
import akka.util.Timeout
import com.ergodicity.core.SessionsTracking.{OptSessContents, FutSessContents}

trait ContentsManager[T] {
  contents: SessionContents[T] =>

  protected def applySessionState(state: SessionState)

  protected def receiveContents: Receive
}

class SessionContents[T](Session: ActorRef) extends Actor with ActorLogging with WhenUnhandled {
  manager: SessionContents[T] with ContentsManager[T] =>

  implicit val timeout = Timeout(100.millis)

  protected[core] val instruments = mutable.Map[Instrument, ActorRef]()

  // Make assumption on parent session state
  var sessionState: SessionState = SessionState.Suspended

  override def preStart() {
    log.info("Start SessionContents with parent session state = " + sessionState)
    Session ! SubscribeTransitionCallBack(self)
  }

  protected def receive = receiveSessionState orElse getInstruments orElse receiveContents orElse whenUnhandled

  private def receiveSessionState: Receive = {
    case CurrentState(Session, state: SessionState) =>
      sessionState = state
      applySessionState(state)

    case Transition(Session, _, to: SessionState) =>
      sessionState = to
      applySessionState(to)
  }

  private def getInstruments: Receive = {
    case GetInstrumentActor(isin) =>
      val instrument = instruments.find(_._1.security.isin == isin)
      log.debug("Get session instrument: " + isin + "; Result = " + instrument)
      sender ! instrument.map(_._2)

    case GetAssignedInstruments =>
      sender ! AssignedInstruments(instruments.keys.toSet)
  }

}

trait FuturesContentsManager extends ContentsManager[FutSessContents] {
  contents: SessionContents[FutSessContents] =>

  private val originalInstrumentState = mutable.Map[Instrument, InstrumentState]()

  protected def applySessionState(state: SessionState) {
    instruments.foreach {
      case (instrument, ref) => ref ! merge(state, originalInstrumentState(instrument))
    }
  }

  protected def receiveContents: Receive = {
    case contents: FutSessContents => handleSessionContents(contents)
  }

  def handleSessionContents(contents: FutSessContents) {
    originalInstrumentState(contents.instrument) = contents.state
    val isin = contents.instrument.security.isin
    // EBAKA BEGIN
    instruments.keys.find(_.security.isin == isin) map {existing =>
      if (existing != contents.instrument) {
        log.error("EXISTING INSTURMENTS = "+existing)
        log.error("NEW INSTRUMENT = "+contents.instrument)
      }
    }
    // EBAKA END
    val instrumentActor = instruments.getOrElseUpdate(contents.instrument, context.actorOf(Props(new InstrumentActor(contents.instrument)), isin.toActorName))
    instrumentActor ! merge(sessionState, contents.state)
  }

  def merge(sessionState: SessionState, instrumentState: InstrumentState) = sessionState match {

    case SessionState.Assigned => instrumentState match {
      case InstrumentState.Online => InstrumentState.Assigned
      case other => other
    }

    case SessionState.Online => instrumentState

    case SessionState.Suspended => instrumentState match {
      case s@(InstrumentState.Canceled | InstrumentState.Completed) => s
      case s@(InstrumentState.Assigned | InstrumentState.Online) => InstrumentState.Suspended
      case other => other
    }

    case SessionState.Canceled => InstrumentState.Canceled

    case SessionState.Completed => InstrumentState.Completed
  }
}

trait OptionsContentsManager extends ContentsManager[OptSessContents] {
  contents: SessionContents[OptSessContents] =>

  protected def applySessionState(state: SessionState) {
    instruments.foreach {
      case (isin, ref) => ref ! InstrumentState(state)
    }
  }

  protected def receiveContents: Receive = {
    case contents: OptSessContents => handleSessionContents(contents)
  }

  protected def handleSessionContents(contents: OptSessContents) {
    val isin = contents.instrument.security.isin
    val instrumentActor = instruments.getOrElseUpdate(contents.instrument, context.actorOf(Props(new InstrumentActor(contents.instrument)), isin.toActorName))
    instrumentActor ! InstrumentState(sessionState)
  }
}

