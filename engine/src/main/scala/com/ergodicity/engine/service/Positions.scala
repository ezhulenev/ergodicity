package com.ergodicity.engine.service

import com.ergodicity.engine.Components.{CreateListener, PosReplication}
import com.ergodicity.engine.Engine
import com.ergodicity.core.position.{TerminatePosition, UpdatePosition, PositionData, Position}
import akka.actor._
import akka.util.duration._
import akka.pattern.ask
import com.ergodicity.cgate.{Connection => _, _}
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import com.ergodicity.core.IsinId
import akka.util.Timeout
import collection.mutable
import com.ergodicity.cgate.repository.Repository
import com.ergodicity.cgate.scheme.Pos
import com.ergodicity.cgate.DataStream.BindingResult
import akka.dispatch.Await
import com.ergodicity.cgate.DataStream.BindingSucceed
import akka.actor.FSM.Transition
import config.Replication.ReplicationParams
import akka.actor.FSM.CurrentState
import akka.actor.FSM.UnsubscribeTransitionCallBack
import com.ergodicity.cgate.DataStream.BindingFailed
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.cgate.DataStream.BindTable
import repository.Repository.{SubscribeSnapshots, Snapshot}

case object PositionsServiceId extends ServiceId

trait Positions {
  engine: Engine =>

  def PosStream: ActorRef

  def Positions: ActorRef
}

trait ManagedPositions extends Positions {
  engine: Engine with Connection with CreateListener with PosReplication =>

  val PosStream = context.actorOf(Props(new DataStream), "PosDataStream")

  val Positions = context.actorOf(Props(new PositionsTracking(PosStream)), "Positions")
  private[this] val positionsManager = context.actorOf(Props(new PositionsManager(this)).withDispatcher("deque-dispatcher"), "PositionsManager")

  registerService(PositionsServiceId, positionsManager)
}

protected[service] class PositionsManager(engine: Engine with Connection with Positions with CreateListener with PosReplication) extends Actor with ActorLogging with WhenUnhandled with Stash {
  import engine._

  val ManagedPositions = Positions

  val underlyingPosListener = listener(underlyingConnection, posReplication(), new DataStreamSubscriber(PosStream))
  val posListener = context.actorOf(Props(new Listener(underlyingPosListener)), "PosListener")

  protected def receive = {
    case ServiceStarted(ConnectionServiceId) =>
      log.info("ConnectionService started, unstash all messages and start PositionsService")
      unstashAll()
      context.become {
        start orElse stop orElse handlePositionsGoesOnline orElse whenUnhandled
      }

    case msg =>
      log.info("Stash message until ConnectionService is not started = " + msg)
      stash()
  }

  private def start: Receive = {
    case Start =>
      posListener ! Listener.Open(ReplicationParams(Combined))
      ManagedPositions ! SubscribeTransitionCallBack(self)
  }

  private def handlePositionsGoesOnline: Receive = {
    case CurrentState(ManagedPositions, PositionsTrackingState.Online) =>
      ManagedPositions ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(PositionsServiceId)

    case Transition(ManagedPositions, _, PositionsTrackingState.Online) =>
      ManagedPositions ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(PositionsServiceId)
  }

  private def stop: Receive = {
    case Stop =>
      posListener ! Listener.Close
      posListener ! Listener.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        ServiceManager ! ServiceStopped(PositionsServiceId)
        context.stop(self)
      }
  }
}

object PositionsTracking {
  def apply(PosStream: ActorRef) = new PositionsTracking(PosStream)

  case class GetPosition(isin: IsinId)

  case object GetOpenPositions

  case class OpenPositions(positions: Iterable[IsinId])

}

sealed trait PositionsTrackingState

object PositionsTrackingState {

  case object Binded extends PositionsTrackingState

  case object LoadingPositions extends PositionsTrackingState

  case object Online extends PositionsTrackingState

}

class PositionsTracking(PosStream: ActorRef) extends Actor with FSM[PositionsTrackingState, Unit] {

  import PositionsTracking._
  import PositionsTrackingState._

  implicit val timeout = Timeout(1.second)

  val positions = mutable.Map[IsinId, ActorRef]()

  // Repositories
  import com.ergodicity.cgate.Protocol.ReadsPosPositions
  val PositionsRepository = context.actorOf(Props(Repository[Pos.position]), "PositionsRepository")

  log.debug("Bind to Pos data stream")

  // Bind to tables
  val bindingResult = (PosStream ? BindTable(Pos.position.TABLE_INDEX, PositionsRepository)).mapTo[BindingResult]
  Await.result(bindingResult, 1.second) match {
    case BindingSucceed(_, _) =>
    case BindingFailed(_, _) => throw new IllegalStateException("Positions data stream in invalid state")
  }

  // Track Data Stream state
  PosStream ! SubscribeTransitionCallBack(self)

  startWith(Binded, ())

  when(Binded) {
    case Event(CurrentState(PosStream, DataStreamState.Online), _) => goto(LoadingPositions)
    case Event(Transition(PosStream, _, DataStreamState.Online), _) => goto(LoadingPositions)
  }

  when(LoadingPositions) {
    case Event(s@Snapshot(PositionsRepository, _), _) =>
      self ! s
      goto(Online)
  }

  when(Online) {
    case Event(GetOpenPositions, _) =>
      sender ! OpenPositions(positions.keys)
      stay()

    case Event(GetPosition(isin), _) =>
      sender ! positions.getOrElseUpdate(isin, context.actorOf(Props(new Position(isin))))
      stay()

    case Event(s@Snapshot(PositionsRepository, _), _) =>
      val snapshot = s.asInstanceOf[Snapshot[Pos.position]]
      log.debug("Got positions repository snapshot, size = " + snapshot.data.size)

      // First terminate discarded positions
      val (_, terminated) = positions.partition {
        case key => snapshot.data.find(_.get_isin_id() == key._1.id).isDefined
      }
      terminated.values.foreach(_ ! TerminatePosition)

      // Update alive positions and open new one
      snapshot.data.map {
        case position =>
          val id = IsinId(position.get_isin_id())
          val data = PositionData(position.get_open_qty(), position.get_buys_qty(), position.get_sells_qty(),
            position.get_pos(), position.get_net_volume_rur(), position.get_last_deal_id())

          val positionActor = positions.getOrElseUpdate(id, context.actorOf(Props(new Position(id)), id.id.toString))
          positionActor ! UpdatePosition(data)
      }

      stay()
  }

  onTransition {
    case Binded -> LoadingPositions =>
      log.debug("Load opened positions")
      // Unsubscribe from updates
      PosStream ! UnsubscribeTransitionCallBack(self)
      // Subscribe for sessions snapshots
      PositionsRepository ! SubscribeSnapshots(self)

    case LoadingPositions -> Online =>
      log.debug("Positions goes online")
  }

}
