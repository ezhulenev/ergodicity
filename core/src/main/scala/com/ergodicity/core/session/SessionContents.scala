package com.ergodicity.core.session

import akka.actor.{ActorLogging, Props, ActorRef, Actor}
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.util.duration._
import com.ergodicity.cgate.WhenUnhandled
import collection.mutable
import com.ergodicity.core.session.SessionActor.{AssignedContents, GetAssignedContents, GetInstrument}
import akka.util.Timeout
import com.ergodicity.core.SessionsTracking.{OptSessContents, FutSessContents}
import com.ergodicity.core.{OptionContract, FutureContract, Security}

trait ContentsManager[T] {
  contents: SessionContents[T] =>

  protected def applySessionState(state: SessionState)

  protected def receiveContents: Receive
}

class SessionContents[T](Session: ActorRef) extends Actor with ActorLogging with WhenUnhandled {
  manager: SessionContents[T] with ContentsManager[T] =>

  implicit val timeout = Timeout(100.millis)

  protected[core] val instruments = mutable.Map[Security, ActorRef]()

  // Make assumption on parent session state
  var sessionState: SessionState = SessionState.Suspended

  override def preStart() {
    log.info("Start SessionContents")
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
    case GetInstrument(security) =>
      val instrument = instruments.find(_._1 == security)
      log.debug("Get session instrument: " + security + "; Result = " + instrument)
      sender ! instrument.map(_._2)

    case GetAssignedContents =>
      sender ! AssignedContents(instruments.keys.toSet)
  }

}

trait FuturesContentsManager extends ContentsManager[FutSessContents] {
  this: SessionContents[FutSessContents] =>

  private val originalInstrumentState = mutable.Map[FutureContract, InstrumentState]()

  protected def applySessionState(state: SessionState) {
    instruments.foreach {
      case (future: FutureContract, ref) => ref ! merge(state, originalInstrumentState(future))
      case (option: OptionContract, _) => throw new IllegalStateException("Doesn't expect OptionContrat here!")
    }
  }

  protected def receiveContents: Receive = {
    case contents: FutSessContents => handleSessionContents(contents)
  }

  def handleSessionContents(contents: FutSessContents) {
    originalInstrumentState(contents.future) = contents.state
    val isin = contents.future.isin
    lazy val creator = new FutureInstrument(contents.future)
    val instrumentActor = instruments.getOrElseUpdate(contents.future, context.actorOf(Props(creator), isin.toActorName))
    instrumentActor ! contents.params
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
  this: SessionContents[OptSessContents] =>

  protected def applySessionState(state: SessionState) {
    instruments.foreach {
      case (isin, ref) => ref ! InstrumentState(state)
    }
  }

  protected def receiveContents: Receive = {
    case contents: OptSessContents => handleSessionContents(contents)
  }

  protected def handleSessionContents(contents: OptSessContents) {
    val isin = contents.option.isin
    lazy val creator = new OptionInstrument(contents.option)
    val instrumentActor = instruments.getOrElseUpdate(contents.option, context.actorOf(Props(creator), isin.toActorName))
    instrumentActor ! contents.params
    instrumentActor ! InstrumentState(sessionState)
  }
}

