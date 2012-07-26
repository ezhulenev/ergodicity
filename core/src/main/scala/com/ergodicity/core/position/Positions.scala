package com.ergodicity.core.position

import com.ergodicity.core.common.IsinId
import akka.actor.{FSM, Props, ActorRef, Actor}
import akka.actor.FSM.{Transition, CurrentState, UnsubscribeTransitionCallBack, SubscribeTransitionCallBack}
import akka.pattern.ask
import akka.util.duration._
import com.ergodicity.cgate.repository.Repository
import com.ergodicity.cgate.scheme.Pos
import com.ergodicity.cgate.DataStreamState
import com.ergodicity.cgate.repository.Repository.{SubscribeSnapshots, Snapshot}
import com.ergodicity.cgate.Protocol.ReadsPosPositions
import com.ergodicity.cgate.repository.ReplicaExtractor.PosPositionsExtractor
import akka.util.Timeout
import akka.dispatch.Await
import com.ergodicity.cgate.DataStream.{BindingFailed, BindingSucceed, BindingResult, BindTable}

object Positions {
  def apply(PosStream: ActorRef) = new Positions(PosStream)
}

sealed trait PositionsState

object PositionsState {
  case object Binded extends PositionsState

  case object Online extends PositionsState
}

sealed trait PositionsData

object PositionsData {

  case object Blank extends PositionsData

  case class TrackingPositions(positions: Map[IsinId, ActorRef] = Map()) extends PositionsData

}

case class TrackPosition(isin: IsinId)

class Positions(PosStream: ActorRef) extends Actor with FSM[PositionsState, PositionsData] {

  import PositionsState._
  import PositionsData._

  implicit val timeout = Timeout(1.second)

  // Repositories
  protected[position] val PositionsRepository = context.actorOf(Props(Repository[Pos.position]), "PositionsRepository")

  log.debug("Bind to Pos data stream")

  // Bind to tables
  val bindingResult = (PosStream ? BindTable(Pos.position.TABLE_INDEX, PositionsRepository)).mapTo[BindingResult]
  Await.result(bindingResult, 1.second) match {
    case BindingSucceed(_, _) =>
    case BindingFailed(_, _) => throw new IllegalStateException("Positions data stream in invalid state")
  }

  // Track Data Stream state
  PosStream ! SubscribeTransitionCallBack(self)

  startWith(Binded, Blank)

  when(Binded) {
    case Event(CurrentState(PosStream, DataStreamState.Online), Blank) => goto(Online) using TrackingPositions()
    case Event(Transition(PosStream, _, DataStreamState.Online), Blank) => goto(Online) using TrackingPositions()
  }

  when(Online) {
    case Event(TrackPosition(isin), TrackingPositions(positions)) if (positions.contains(isin)) =>
      sender ! positions(isin)
      stay()

    case Event(TrackPosition(isin), TrackingPositions(positions)) if (!positions.contains(isin)) =>
      // Create new positions
      val position = context.actorOf(Props(new Position(isin)))
      sender ! position
      stay() using TrackingPositions(positions + (isin -> position))

    case Event(s@Snapshot(PositionsRepository, _), TrackingPositions(positions)) =>
      val snapshot = s.asInstanceOf[Snapshot[Pos.position]]
      log.debug("Got positions repository snapshot, size = " + snapshot.data.size)

      // First terminate old positions
      val (alive, terminated) = positions.partition {
        case key =>
          snapshot.data.find(_.get_isin_id() == key._1.id).isDefined
      }
      terminated.values.foreach(_ ! TerminatePosition)

      // Update alive positions and open new one
      val updatedAlive = snapshot.data.map {
        case position =>
          val id = IsinId(position.get_isin_id())
          val data = PositionData(position.get_open_qty(),
            position.get_buys_qty(),
            position.get_sells_qty(),
            position.get_pos(),
            position.get_net_volume_rur(),
            position.get_last_deal_id()
          )
          val positionActor = alive.get(id) getOrElse {
            // Start new position actor for given IsinId
            context.actorOf(Props(new Position(id)), id.id.toString)
          }
          positionActor ! UpdatePosition(data)
          (id -> positionActor)
      }

      stay() using TrackingPositions(updatedAlive.toMap ++ terminated)
  }

  onTransition {
    case Binded -> Online =>
      log.debug("Positions goes online")

      // Unsubscribe from updates
      PosStream ! UnsubscribeTransitionCallBack(self)

      // Subscribe for sessions snapshots
      PositionsRepository ! SubscribeSnapshots(self)
  }

}