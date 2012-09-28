package com.ergodicity.engine.service

import com.ergodicity.engine.ReplicationScheme.{OptTradesReplication, FutTradesReplication}
import com.ergodicity.cgate.{Listener, DataStreamSubscriber, DataStream, DataStreamState}
import akka.actor.{FSM, Props, Actor, LoggingFSM}
import akka.util.duration._
import akka.pattern.ask
import akka.pattern.pipe
import com.ergodicity.cgate.config.Replication
import ru.micexrts.cgate.{Connection => CGConnection}
import com.ergodicity.engine.underlying.{ListenerFactory, UnderlyingConnection, UnderlyingListener}
import com.ergodicity.engine.{Services, Engine}
import com.ergodicity.engine.service.TradesDataState.StreamStates
import akka.util.Timeout
import com.ergodicity.core.trade.TradesTracking
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.core.SessionsTracking.{OngoingSessionTransition, OngoingSession, SubscribeOngoingSessions}
import com.ergodicity.core.session.SessionActor.{AssignedContents, GetAssignedContents}
import com.ergodicity.cgate.config.Replication.ReplicationMode.Online
import com.ergodicity.cgate.config.Replication.ReplicationParams
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.core.trade.TradesTracking.SubscribeTrades

object TradesData {

  implicit case object TradesData extends ServiceId

}

trait TradesData {
  this: Services =>

  import TradesData._

  def engine: Engine with UnderlyingConnection with UnderlyingListener with FutTradesReplication with OptTradesReplication

  lazy val creator = new TradesDataService(engine.listenerFactory, engine.underlyingConnection, engine.futTradesReplication, engine.optTradesReplication)
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

protected[service] class TradesDataService(listener: ListenerFactory, underlyingConnection: CGConnection, futTradeReplication: Replication, optTradeReplication: Replication)
                                          (implicit val services: Services, id: ServiceId) extends Actor with LoggingFSM[TradesDataState, StreamStates] with Service {

  import TradesDataState._
  import services._

  implicit val timeout = Timeout(5.second)

  val instrumentData = service(InstrumentData.InstrumentData)

  // Trades streams
  val FutTradeStream = context.actorOf(Props(new DataStream), "FutTradeStream")
  val OptTradeStream = context.actorOf(Props(new DataStream), "OptTradeStream")

  // Trades listeners
  private[this] val underlyingFutTradeListener = listener(underlyingConnection, futTradeReplication(), new DataStreamSubscriber(FutTradeStream))
  private[this] val futTradeListener = context.actorOf(Props(new Listener(underlyingFutTradeListener)).withDispatcher(Engine.ReplicationDispatcher), "FutTradeListener")

  private[this] val underlyingOptTradeListener = listener(underlyingConnection, optTradeReplication(), new DataStreamSubscriber(OptTradeStream))
  private[this] val optTradeListener = context.actorOf(Props(new Listener(underlyingOptTradeListener)).withDispatcher(Engine.ReplicationDispatcher), "OptTradeListener")

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