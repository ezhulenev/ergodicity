package com.ergodicity.capture

import akka.actor.{Props, FSM, Actor, ActorRef}
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import com.ergodicity.core.Security
import com.ergodicity.cgate.scheme._
import com.ergodicity.cgate.DataStreamState
import com.ergodicity.cgate.Protocol._
import com.ergodicity.capture.ReplicaExtractor._
import com.ergodicity.capture.Repository.{Snapshot, SubscribeSnapshots}
import scalaz._
import Scalaz._

case class SubscribeMarketContents(ref: ActorRef)

case class FuturesContents(contents: Map[Int, Security])

case class OptionsContents(contents: Map[Int, Security])

case object MarketContentsInitialized

sealed trait MarketContentsState

object MarketContentsState {

  case object Initializing extends MarketContentsState

  case object Online extends MarketContentsState

}

sealed trait MarketContentsData

object MarketContentsData {

  case object Blank extends MarketContentsData

  case class StreamStates(futures: Option[DataStreamState], options: Option[DataStreamState]) extends MarketContentsData

}

class MarketContentsCapture(FutInfoStream: ActorRef, OptInfoStream: ActorRef,
                            repository: SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository) extends Actor with FSM[MarketContentsState, MarketContentsData] {

  import MarketContentsState._
  import MarketContentsData._

  var subscribers: Seq[ActorRef] = Seq()

  // Repositories
  val SessionsRepository = context.actorOf(Props(Repository[FutInfo.session]), "SessionsRepository")
  SessionsRepository ! SubscribeSnapshots(self)

  val FutSessContentsRepository = context.actorOf(Props(Repository[FutInfo.fut_sess_contents]), "FutSessContentsRepository")
  FutSessContentsRepository ! SubscribeSnapshots(self)

  val OptSessContentsRepository = context.actorOf(Props(Repository[OptInfo.opt_sess_contents]), "OptSessContentsRepository")
  OptSessContentsRepository ! SubscribeSnapshots(self)

  // Route stream events to repositories
  val futuresRouter = context.actorOf(Props(new Router(FutInfoStream, Route(FutSessContentsRepository).table(FutInfo.fut_sess_contents.TABLE_INDEX) :: Nil)))
  val optionsRouter = context.actorOf(Props(new Router(OptInfoStream, Route(OptSessContentsRepository).table(OptInfo.opt_sess_contents.TABLE_INDEX) :: Nil)))

  // Track their states
  FutInfoStream ! SubscribeTransitionCallBack(self)
  OptInfoStream ! SubscribeTransitionCallBack(self)

  // Initialize
  startWith(Initializing, StreamStates(None, None))

  when(Initializing) {
    // Handle FutInfo and OptInfo data streams state updates
    case Event(CurrentState(FutInfoStream, state: DataStreamState), binding: StreamStates) =>
      handleStreamState(binding.copy(futures = Some(state)))

    case Event(CurrentState(OptInfoStream, state: DataStreamState), binding: StreamStates) =>
      handleStreamState(binding.copy(options = Some(state)))

    case Event(Transition(FutInfoStream, _, state: DataStreamState), binding: StreamStates) =>
      handleStreamState(binding.copy(futures = Some(state)))

    case Event(Transition(OptInfoStream, _, state: DataStreamState), binding: StreamStates) =>
      handleStreamState(binding.copy(options = Some(state)))
  }

  when(Online) {
    case Event("I'm not going to see any events here", _) => stay()
  }

  onTransition {
    case Initializing -> Online =>
      log.info("Market Contents goes online")
      subscribers.foreach(_ ! MarketContentsInitialized)
  }

  whenUnhandled {
    case Event(SubscribeMarketContents(ref), _) => subscribers = ref +: subscribers; stay()

    // Handle session contents snapshots
    case Event(Snapshot(SessionsRepository, sessions), _) =>
      sessions.asInstanceOf[Iterable[FutInfo.session]].foreach(repository.saveSession(_))
      stay()

    case Event(Snapshot(FutSessContentsRepository, data), _) =>
      val futures = data.asInstanceOf[Iterable[FutInfo.fut_sess_contents]].foldLeft(Map[Int, Security]()) {
        case (m, r) =>
          repository.saveSessionContents(r)
          m + (r.get_isin_id() -> com.ergodicity.core.session.Implicits.FutureInstrument.security(r))
      }
      subscribers.foreach(_ ! FuturesContents(futures))
      stay()

    case Event(Snapshot(OptSessContentsRepository, data), _) =>
      val options = data.asInstanceOf[Iterable[OptInfo.opt_sess_contents]].foldLeft(Map[Int, Security]()) {
        case (m, r) =>
          repository.saveSessionContents(r)
          m + (r.get_isin_id() -> com.ergodicity.core.session.Implicits.OptionInstrument.security(r))
      }
      subscribers.foreach(_ ! OptionsContents(options))
      stay()
  }

  initialize

  protected def handleStreamState(state: StreamStates): State = {
    (state.futures <**> state.options)((_, _)) match {
      case Some((DataStreamState.Online, DataStreamState.Online)) => goto(Online) using Blank
      case _ => stay() using state
    }
  }

}