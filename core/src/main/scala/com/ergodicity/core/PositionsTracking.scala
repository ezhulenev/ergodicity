package com.ergodicity.core

import akka.actor.{Props, FSM, Actor, ActorRef}
import akka.util.Timeout
import collection.mutable
import com.ergodicity.cgate.repository.Repository
import com.ergodicity.cgate.scheme.Pos
import com.ergodicity.cgate.DataStream.BindingResult
import akka.dispatch.Await
import akka.actor.FSM._
import akka.util.duration._
import com.ergodicity.cgate.DataStreamState
import com.ergodicity.cgate.repository.Repository.{SubscribeSnapshots, Snapshot}
import position.{Position, PositionDynamics, PositionActor}
import com.ergodicity.cgate.DataStream.BindingSucceed
import position.PositionActor.UpdatePosition
import akka.actor.FSM.Transition
import akka.actor.FSM.CurrentState
import akka.pattern.ask
import com.ergodicity.cgate.DataStream.BindingFailed
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.cgate.DataStream.BindTable
import session.SessionActor.AssignedInstruments

object PositionsTracking {
  def apply(PosStream: ActorRef) = new PositionsTracking(PosStream)

  case class GetPositionActor(isin: Isin)

  case class TrackedPosition(isin: Isin, positionActor: ActorRef)

  case object GetOpenPositions

  case class OpenPositions(positions: Iterable[Isin])

  // Failures

  class PositionsTrackingException(message: String) extends RuntimeException(message)
}

sealed trait PositionsTrackingState

object PositionsTrackingState {

  case object Binded extends PositionsTrackingState

  case object LoadingPositions extends PositionsTrackingState

  case object Online extends PositionsTrackingState

}

class PositionsTracking(PosStream: ActorRef) extends Actor with FSM[PositionsTrackingState, AssignedInstruments] {

  import PositionsTracking._
  import PositionsTrackingState._

  implicit val timeout = Timeout(1.second)

  val positions = mutable.Map[Isin, ActorRef]()

  // Repositories

  import com.ergodicity.cgate.Protocol.ReadsPosPositions

  val PositionsRepository = context.actorOf(Props(Repository[Pos.position]), "PositionsRepository")

  log.debug("Bind to Pos data stream")

  // Bind to tables
  val bindingResult = (PosStream ? BindTable(Pos.position.TABLE_INDEX, PositionsRepository)).mapTo[BindingResult]
  Await.result(bindingResult, 1.second) match {
    case BindingSucceed(_, _) =>
    case BindingFailed(_, _) => throw new PositionsTrackingException("Positions data stream in invalid state")
  }

  // Track Data Stream state
  PosStream ! SubscribeTransitionCallBack(self)

  startWith(Binded, AssignedInstruments(Set()))

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

    case Event(GetPositionActor(isin), _) =>
      sender ! TrackedPosition(isin, positions.getOrElseUpdate(isin, context.actorOf(Props(new PositionActor(isin)))))
      stay()

    case Event(s@Snapshot(PositionsRepository, _), assigned) =>
      val snapshot = s.asInstanceOf[Snapshot[Pos.position]]
      log.debug("Got positions repository snapshot, size = " + snapshot.data.size)

      // First send empty data for all discarded positions
      val (_, discarded) = positions.partition {
        case key => snapshot.data.find(pos => assigned.isin(IsinId(pos.get_isin_id())) == key._1).isDefined
      }
      discarded.values.foreach(_ ! UpdatePosition(Position.flat, PositionDynamics.empty))

      // Update alive positions and open new one
      snapshot.data.map {
        case pos =>
          val isin = assigned.isin(IsinId(pos.get_isin_id()))
          val position = Position(pos.get_pos())
          val dynamics = PositionDynamics(
            pos.get_open_qty(),
            pos.get_buys_qty(),
            pos.get_sells_qty(),
            pos.get_net_volume_rur(),
            if (pos.get_last_deal_id() == 0) None else Some(pos.get_last_deal_id())
          )

          val positionActor = positions.getOrElseUpdate(isin, context.actorOf(Props(new PositionActor(isin)), isin.toActorName))
          positionActor ! UpdatePosition(position, dynamics)
      }

      stay()
  }


  whenUnhandled {
    case Event(assigned: AssignedInstruments, old) =>
      log.debug("Assigned instruments = " + assigned)
      stay() using assigned
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

