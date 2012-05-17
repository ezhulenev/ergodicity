package com.ergodicity.capture

import java.io.File
import plaza2.RequestType.CombinedDynamic
import plaza2.{TableSet, Connection => P2Connection, DataStream => P2DataStream}
import akka.actor.{Props, FSM, Actor, ActorRef}
import com.ergodicity.plaza2.Repository._
import com.ergodicity.plaza2.{DataStreamState, Repository, DataStream}
import com.ergodicity.plaza2.scheme.{Deserializer, OptInfo, FutInfo}
import com.ergodicity.plaza2.{DataStream, Repository, DataStreamState}
import com.ergodicity.plaza2.DataStream.{Open, JoinTable, SetLifeNumToIni}
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import com.ergodicity.core.session.Security
import com.ergodicity.core.session.Security


case class SubscribeMarketContents(ref: ActorRef)

case class FuturesContents(contents: Map[Int, Security])

case class OptionsContents(contents: Map[Int, Security])

case object InitializeMarketContents

case object MarketContentsInitialized

sealed trait MarketContentsState

object MarketContentsState {

  case object Idle extends MarketContentsState

  case object Initializing extends MarketContentsState

  case object Online extends MarketContentsState

}

class MarketContentsCapture(underlyingConnection: P2Connection, scheme: Plaza2Scheme,
                            sessionTracker: SessionTracker,
                            futSessionTracker: FutSessionContentsTracker,
                            optSessionTracker: OptSessionContentsTracker) extends Actor with FSM[MarketContentsState, Unit] {

  import MarketContentsState._

  var subscribers: Seq[ActorRef] = Seq()

  val FORTS_FUTINFO_REPL = "FORTS_FUTINFO_REPL"
  val FORTS_OPTINFO_REPL = "FORTS_OPTINFO_REPL"

  // Track market contents
  val sessionsRepository = context.actorOf(Props(Repository[FutInfo.SessionRecord]), "SessionsRepository")
  sessionsRepository ! SubscribeSnapshots(self)

  var futSessContentsOnline = false;
  val futSessContentsRepository = context.actorOf(Props(Repository[FutInfo.SessContentsRecord]), "FutSessContentsRepository")
  futSessContentsRepository ! SubscribeSnapshots(self)

  var optSessContentsOnline = false;
  val optSessContentsRepository = context.actorOf(Props(Repository[OptInfo.SessContentsRecord]), "OptSessContentsRepository")
  optSessContentsRepository ! SubscribeSnapshots(self)

  startWith(Idle, ())

  when(Idle) {
    case Event(SubscribeMarketContents(ref), _) => subscribers = ref +: subscribers; stay()
    case Event(InitializeMarketContents, _) => goto(Initializing)
  }

  when(Initializing) {
    case Event(Transition(ref, _, DataStreamState.Online), _) if (ref == futInfoStream) =>
      futSessContentsOnline = true;
      if (futSessContentsOnline && optSessContentsOnline) goto(Online) else stay()

    case Event(Transition(ref, _, DataStreamState.Online), _) if (ref == optInfoStream) =>
      optSessContentsOnline = true;
      if (futSessContentsOnline && optSessContentsOnline) goto(Online) else stay()
  }

  when(Online) {
    case Event("I'm not going to see any events here", _) => stay()
  }

  initialize

  onTransition {
    case Idle -> Initializing =>
      log.info("Initialize Market Contents")
      futInfoStream ! JoinTable("fut_sess_contents", futSessContentsRepository, implicitly[Deserializer[FutInfo.SessContentsRecord]])
      futInfoStream ! SubscribeTransitionCallBack(self)
      futInfoStream ! Open(underlyingConnection)

      optInfoStream ! JoinTable("opt_sess_contents", optSessContentsRepository, implicitly[Deserializer[OptInfo.SessContentsRecord]])
      optInfoStream ! SubscribeTransitionCallBack(self)
      optInfoStream ! Open(underlyingConnection)

    case Initializing -> Online =>
      log.info("Market Contents goes online")
      subscribers.foreach(_ ! MarketContentsInitialized)
  }

  whenUnhandled {
    case Event(Transition(fsm, _, _), _) if (fsm == futInfoStream) => stay()
    case Event(CurrentState(fsm, _), _) if (fsm == futInfoStream) => stay()

    case Event(Transition(fsm, _, _), _) if (fsm == optInfoStream) => stay()
    case Event(CurrentState(fsm, _), _) if (fsm == optInfoStream) => stay()

    // Handle session contents snapshots
    case Event(Snapshot(repo, sessions), _) if (repo == sessionsRepository) =>
      sessions.asInstanceOf[Iterable[FutInfo.SessionRecord]].foreach(sessionTracker.saveSession(_))
      stay()

    case Event(Snapshot(repo, data), _) if (repo == futSessContentsRepository) =>
      val futures = data.asInstanceOf[Iterable[FutInfo.SessContentsRecord]].foldLeft(Map[Int, Security]()) {
        case (m, r) =>
          futSessionTracker.saveSessionContents(r)
          m + (r.isinId -> com.ergodicity.core.session.BasicFutInfoConverter(r))
      }
      subscribers.foreach(_ ! FuturesContents(futures))
      stay()

    case Event(Snapshot(repo, data), _) if (repo == optSessContentsRepository) =>
      val options = data.asInstanceOf[Iterable[OptInfo.SessContentsRecord]].foldLeft(Map[Int, Security]()) {
        case (m, r) =>
          optSessionTracker.saveSessionContents(r)
          m + (r.isinId -> com.ergodicity.core.session.BasicOptInfoConverter(r))
      }
      subscribers.foreach(_ ! OptionsContents(options))
      stay()
  }

  // Market Contents data streams
  lazy val futInfoStream = {
    val futInfoIni = new File(scheme.futInfo)
    val futInfoTableSet = TableSet(futInfoIni)
    val underlyingStream = P2DataStream(FORTS_FUTINFO_REPL, CombinedDynamic, futInfoTableSet)
    val futInfoStream = context.actorOf(Props(new DataStream(underlyingStream)), FORTS_FUTINFO_REPL)
    futInfoStream ! SetLifeNumToIni(futInfoIni)
    futInfoStream
  }

  lazy val optInfoStream = {
    val optInfoIni = new File(scheme.optInfo)
    val optInfoTableSet = TableSet(optInfoIni)
    val underlyingStream = P2DataStream(FORTS_OPTINFO_REPL, CombinedDynamic, optInfoTableSet)
    val optInfoStream = context.actorOf(Props(new DataStream(underlyingStream)), FORTS_OPTINFO_REPL)
    optInfoStream ! SetLifeNumToIni(optInfoIni)
    optInfoStream
  }

}