package com.ergodicity.engine.service

import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor._
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.cgate._
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.core.PositionsTracking
import com.ergodicity.core.PositionsTracking.{GetTrackedPosition, GetPositions}
import com.ergodicity.core.SessionsTracking.OngoingSession
import com.ergodicity.core.SessionsTracking.OngoingSessionTransition
import com.ergodicity.core.SessionsTracking.SubscribeOngoingSessions
import com.ergodicity.core.session.SessionActor.AssignedContents
import com.ergodicity.core.session.SessionActor.GetAssignedContents
import com.ergodicity.engine.Listener.PosListener
import com.ergodicity.engine.service.PortfolioState.StreamState
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.engine.{Services, Engine}
import scala.Some

object Portfolio {

  implicit case object Portfolio extends ServiceId

}

trait Portfolio {
  this: Services =>

  import Portfolio._

  def engine: Engine with PosListener

  register(Props(new PortfolioService(engine.posListener)), dependOn = InstrumentData.InstrumentData :: Nil)
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

protected[service] class PortfolioService(pos: ListenerBinding)
                                         (implicit val services: Services, id: ServiceId) extends Actor with LoggingFSM[PortfolioState, StreamState] with Service {

  import PortfolioState._
  import services._

  implicit val timeout = Timeout(5.second)

  val instrumentData = service(InstrumentData.InstrumentData)

  val PosStream = context.actorOf(Props(new DataStream), "PosDataStream")
  PosStream ! SubscribeTransitionCallBack(self)

  val Positions = context.actorOf(Props(new PositionsTracking(PosStream)), "Positions")

  pos.bind(new DataStreamSubscriber(PosStream))
  val posListener = context.actorOf(Props(new Listener(pos.listener)).withDispatcher(Engine.ReplicationDispatcher), "PosListener")

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
      (ref ? GetAssignedContents) pipeTo self
      stay()

    case Event(assigned: AssignedContents, _) =>
      Positions ! assigned
      posListener ! Listener.Open(ReplicationParams(Combined))

      goto(StartingPositionsTracker)

    case Event(FSM.StateTimeout, _) => failed("Timed out assigning contents")
  }

  when(StartingPositionsTracker, stateTimeout = 30.seconds) {
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
      (ref ? GetAssignedContents) pipeTo self
      stay()

    case Event(assigned: AssignedContents, _) =>
      Positions ! assigned
      stay()

    case Event(CurrentState(PosStream, state: DataStreamState), _) =>
      stay() using StreamState(Some(state))

    case Event(Transition(PosStream, _, to: DataStreamState), _) =>
      stay() using StreamState(Some(to))

    case Event(GetPositions, _) =>
      Positions.tell(GetPositions, sender)
      stay()

    case Event(get@GetTrackedPosition(_), _) =>
      Positions.tell(get, sender)
      stay()
  }

  private def shutDown: State = {
    posListener ! Listener.Dispose
    serviceStopped
    stop(FSM.Shutdown)
  }
}