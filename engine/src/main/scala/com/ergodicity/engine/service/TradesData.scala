package com.ergodicity.engine.service

import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.{FSM, Props, Actor, LoggingFSM}
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.cgate._
import com.ergodicity.cgate.config.Replication.ReplicationMode.Online
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.core.SessionsTracking.OngoingSession
import com.ergodicity.core.SessionsTracking.OngoingSessionTransition
import com.ergodicity.core.SessionsTracking.SubscribeOngoingSessions
import com.ergodicity.core.session.SessionActor.AssignedContents
import com.ergodicity.core.session.SessionActor.GetAssignedContents
import com.ergodicity.core.trade.TradesTracking
import com.ergodicity.core.trade.TradesTracking.SubscribeTrades
import com.ergodicity.engine.Listener.{OptTradesListener, FutTradesListener}
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.engine.service.TradesDataState.StreamStates
import com.ergodicity.engine.{Services, Engine}

object TradesData {

  implicit case object TradesData extends ServiceId

}

trait TradesData {
  this: Services =>

  import TradesData._

  def engine: Engine with FutTradesListener with OptTradesListener

  private[this] lazy val creator = new TradesDataService(engine.futTradesListener, engine.optTradesListener)
  register(Props(creator), dependOn = InstrumentData.InstrumentData :: Nil)
}

protected[service] sealed trait TradesDataState

object TradesDataState {

  case object Idle extends TradesDataState

  case object AssigningInstruments extends TradesDataState

  case object StartingTradesTracker extends TradesDataState

  case object Started extends TradesDataState

  case object Stopping extends TradesDataState

  case class StreamStates(fut: Option[DataStreamState], opt: Option[DataStreamState])

}

protected[service] class TradesDataService(futTrade: ListenerDecorator, optTrade: ListenerDecorator)
                                          (implicit val services: Services, id: ServiceId) extends Actor with LoggingFSM[TradesDataState, StreamStates] with Service {

  import TradesDataState._
  import services._

  implicit val timeout = Timeout(5.second)

  val instrumentData = service(InstrumentData.InstrumentData)

  // Trades streams
  val FutTradeStream = context.actorOf(Props(new DataStream), "FutTradeStream")
  val OptTradeStream = context.actorOf(Props(new DataStream), "OptTradeStream")

  // Trades listeners
  futTrade.bind(new DataStreamSubscriber(FutTradeStream))
  private[this] val futTradeListener = context.actorOf(Props(new Listener(futTrade.listener)).withDispatcher(Engine.ReplicationDispatcher), "FutTradesListener")

  optTrade.bind(new DataStreamSubscriber(OptTradeStream))
  private[this] val optTradeListener = context.actorOf(Props(new Listener(optTrade.listener)).withDispatcher(Engine.ReplicationDispatcher), "OptTradesListener")

  val Trades = context.actorOf(Props(new TradesTracking(FutTradeStream, OptTradeStream)), "TradesTracking")

  override def preStart() {
    FutTradeStream ! SubscribeTransitionCallBack(self)
    OptTradeStream ! SubscribeTransitionCallBack(self)
  }

  startWith(Idle, StreamStates(None, None))

  when(Idle) {
    case Event(Start, _) =>
      log.info("Start " + id + " service")
      instrumentData ! SubscribeOngoingSessions(self)

      goto(AssigningInstruments)
  }

  when(AssigningInstruments, stateTimeout = 10.seconds) {
    case Event(session@OngoingSession(_, ref), _) =>
      log.debug("Got ongoing session = " + session)
      (ref ? GetAssignedContents) pipeTo self
      stay()

    case Event(assigned: AssignedContents, _) =>
      Trades ! assigned
      futTradeListener ! Listener.Open(ReplicationParams(Online))
      optTradeListener ! Listener.Open(ReplicationParams(Online))

      goto(StartingTradesTracker)

    case Event(FSM.StateTimeout, _) => failed("Timed out assigning contents")
  }

  when(StartingTradesTracker, stateTimeout = 10.seconds) {
    case Event(CurrentState(FutTradeStream, state: DataStreamState), states) => startUp(states.copy(fut = Some(state)))
    case Event(CurrentState(OptTradeStream, state: DataStreamState), states) => startUp(states.copy(opt = Some(state)))
    case Event(Transition(FutTradeStream, _, to: DataStreamState), states) => startUp(states.copy(fut = Some(to)))
    case Event(Transition(OptTradeStream, _, to: DataStreamState), states) => startUp(states.copy(opt = Some(to)))

    case Event(FSM.StateTimeout, _) => failed("Starting timed out")
  }

  when(Started) {
    case Event(Stop, states) =>
      log.info("Stop " + id + " service")
      futTradeListener ! Listener.Close
      optTradeListener ! Listener.Close
      goto(Stopping)
  }

  when(Stopping, stateTimeout = 10.seconds) {
    case Event(Transition(FutTradeStream, _, to: DataStreamState), states) => shutDown(states.copy(fut = Some(to)))
    case Event(Transition(OptTradeStream, _, to: DataStreamState), states) => shutDown(states.copy(opt = Some(to)))

    case Event(FSM.StateTimeout, states@StreamStates(Some(DataStreamState.Closed), Some(DataStreamState.Closed))) => shutDown(states)

    case Event(FSM.StateTimeout, _) => failed("Stopping timed out")
  }

  onTransition {
    case StartingTradesTracker -> Started => serviceStarted
  }

  whenUnhandled {
    case Event(OngoingSessionTransition(from, to@OngoingSession(_, ref)), _) =>
      log.debug("Got ongoing session transition; From = " + from + "; To = " + to)
      (ref ? GetAssignedContents) pipeTo self
      stay()

    case Event(assigned: AssignedContents, _) =>
      Trades ! assigned
      stay()

    case Event(subscribe: SubscribeTrades, _) =>
      Trades ! subscribe
      stay()
  }

  private def shutDown(states: StreamStates) = states match {
    case StreamStates(Some(DataStreamState.Closed), Some(DataStreamState.Closed)) =>
      futTradeListener ! Listener.Dispose
      optTradeListener ! Listener.Dispose
      serviceStopped
      stop(FSM.Shutdown)
    case _ => stay() using states
  }

  private def startUp(states: StreamStates) = states match {
    case StreamStates(Some(DataStreamState.Online), Some(DataStreamState.Online)) => goto(Started)
    case _ => stay() using states
  }

  initialize
}