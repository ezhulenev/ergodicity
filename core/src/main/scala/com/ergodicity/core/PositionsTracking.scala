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
import position.Position
import com.ergodicity.cgate.DataStream.BindingSucceed
import position.Position.UpdatePosition
import akka.actor.FSM.Transition
import akka.actor.FSM.CurrentState
import akka.pattern.ask
import scala.Some
import com.ergodicity.cgate.DataStream.BindingFailed
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.cgate.DataStream.BindTable

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

      // First send empty data for all discarded positions
      val (_, discarded) = positions.partition {
        case key => snapshot.data.find(_.get_isin_id() == key._1.id).isDefined
      }
      discarded.values.foreach(_ ! UpdatePosition(Position.Data()))

      // Update alive positions and open new one
      snapshot.data.map {
        case position =>
          val id = IsinId(position.get_isin_id())
          val data = Position.Data(position.get_open_qty(), position.get_buys_qty(), position.get_sells_qty(),
            position.get_pos(), position.get_net_volume_rur(), if (position.get_last_deal_id() == 0) None else Some(position.get_last_deal_id()))

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

