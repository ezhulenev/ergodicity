package com.ergodicity.engine.service

import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor._
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.duration._
import com.ergodicity.cgate._
import com.ergodicity.cgate.config.Replication
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.core.PositionsTracking
import com.ergodicity.core.SessionsTracking.OngoingSession
import com.ergodicity.core.SessionsTracking.OngoingSessionTransition
import com.ergodicity.core.SessionsTracking.SubscribeOngoingSessions
import com.ergodicity.core.session.SessionActor.AssignedInstruments
import com.ergodicity.core.session.SessionActor.GetAssignedInstruments
import com.ergodicity.engine.ReplicationScheme.PosReplication
import com.ergodicity.engine.service.PortfolioState.StreamState
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.engine.underlying.{ListenerFactory, UnderlyingConnection, UnderlyingListener}
import com.ergodicity.engine.{Services, Engine}
import ru.micexrts.cgate.{Connection => CGConnection}
import scala.Some

object Portfolio {

  implicit case object Portfolio extends ServiceId

}

trait Portfolio {
  this: Services =>

  import Portfolio._

  def engine: Engine with UnderlyingConnection with UnderlyingListener with PosReplication

  register(Props(new PortfolioService(engine.listenerFactory, engine.underlyingConnection, engine.posReplication)), dependOn = InstrumentData.InstrumentData :: Nil)
}

protected[service] sealed trait PortfolioState

object PortfolioState {

  case object Idle extends PortfolioState

  case object AssigningInstruments extends PortfolioState

  case object StartingPositionsTracker extends PortfolioState

  case object Started extends PortfolioState

  case object Stopping extends PortfolioState

  case class StreamState(pos: Option[DataStreamState])

}

protected[service] class PortfolioService(listener: ListenerFactory, underlyingConnection: CGConnection, posReplication: Replication)
                                         (implicit val services: Services, id: ServiceId) extends Actor with LoggingFSM[PortfolioState, StreamState] with Service {

  import PortfolioState._
  import services._

  val instrumentData = service(InstrumentData.InstrumentData)

  val PosStream = context.actorOf(Props(new DataStream), "PosDataStream")
  PosStream ! SubscribeTransitionCallBack(self)

  val Positions = context.actorOf(Props(new PositionsTracking(PosStream)), "Positions")

  val underlyingPosListener = listener(underlyingConnection, posReplication(), new DataStreamSubscriber(PosStream))
  val posListener = context.actorOf(Props(new Listener(underlyingPosListener)).withDispatcher(Engine.ReplicationDispatcher), "PosListener")

  startWith(Idle, StreamState(None))

  when(Idle) {
    case Event(Start, _) =>
      log.info("Start " + id + " service")
      instrumentData ! SubscribeOngoingSessions(self)

      goto(AssigningInstruments)
  }

  when(AssigningInstruments, stateTimeout = 10.seconds) {
    case Event(session@OngoingSession(_, ref), _) =>
      log.debug("Got ongoing session = " + session)
      (ref ? GetAssignedInstruments) pipeTo self
      stay()

    case Event(assigned: AssignedInstruments, _) =>
      Positions ! assigned
      posListener ! Listener.Open(ReplicationParams(Combined))

      goto(StartingPositionsTracker)

    case Event(FSM.StateTimeout, _) => failed("Timed out assigning instruments")
  }

  when(StartingPositionsTracker, stateTimeout = 10.seconds) {
    case Event(Transition(PosStream, _, state@DataStreamState.Online), _) =>
      goto(Started) using StreamState(Some(state))

    case Event(FSM.StateTimeout, _) => failed("Timed out waiting Pos stream")
  }

  when(Started) {
    case Event(Stop, states) =>
      log.info("Stop " + id + " service")
      posListener ! Listener.Close
      goto(Stopping)
  }

  when(Stopping, stateTimeout = 10.seconds) {
    case Event(Transition(PosStream, _, state@DataStreamState.Closed), _) => shutDown

    case Event(FSM.StateTimeout, StreamState(Some(DataStreamState.Closed))) => shutDown

    case Event(FSM.StateTimeout, _) => failed("Stopping timed out")
  }

  onTransition {
    case StartingPositionsTracker -> Started => serviceStarted
  }

  whenUnhandled {
    case Event(OngoingSessionTransition(from, to@OngoingSession(_, ref)), _) =>
      log.debug("Got ongoing session transition; From = " + from + "; To = " + to)
      (ref ? GetAssignedInstruments) pipeTo self
      stay()

    case Event(assigned: AssignedInstruments, _) =>
      Positions ! assigned
      stay()

    case Event(CurrentState(PosStream, state: DataStreamState), _) =>
      stay() using StreamState(Some(state))

    case Event(Transition(PosStream, _, to: DataStreamState), _) =>
      stay() using StreamState(Some(to))
  }

  private def shutDown: State = {
    posListener ! Listener.Dispose
    serviceStopped
    stop(FSM.Shutdown)
  }
}