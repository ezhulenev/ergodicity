package com.ergodicity.core.position

import com.ergodicity.plaza2.scheme.Pos.PositionRecord
import com.ergodicity.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import com.ergodicity.core.common.IsinId
import com.ergodicity.plaza2.{DataStreamState, Repository}
import akka.actor.{FSM, Props, ActorRef, Actor}
import com.ergodicity.core.position.Positions.BindPositions
import com.ergodicity.plaza2.DataStream.BindTable
import com.ergodicity.plaza2.scheme.{Pos, Deserializer}
import akka.actor.FSM.{Transition, CurrentState, UnsubscribeTransitionCallBack, SubscribeTransitionCallBack}

object Positions {
  def apply(PosStream: ActorRef) = new Positions(PosStream)

  case object BindPositions

}

sealed trait PositionsState

object PositionsState {

  case object Idle extends PositionsState

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

  // Repositories
  protected[position] val PositionsRepository = context.actorOf(Props(Repository[PositionRecord]), "PositionsRepository")

  startWith(Idle, Blank)

  when(Idle) {
    case Event(BindPositions, Blank) => goto(Binded)
  }

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
      val snapshot = s.asInstanceOf[Snapshot[PositionRecord]]
      log.debug("Got positions repository snapshot, size = " + snapshot.data.size)

      // First terminate old positions
      val (alive, terminated) = positions.partition {
        case key =>
          snapshot.data.find(_.isin_id == key._1.id).isDefined
      }
      terminated.values.foreach(_ ! TerminatePosition)

      // Update alive positions and open new one
      val updatedAlive = snapshot.data.map {
        case position =>
          val id = IsinId(position.isin_id)
          val data = PositionData(position.open_qty, position.buys_qty, position.sells_qty, position.pos, position.net_volume_rur, position.last_deal_id)
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
    case Idle -> Binded =>
      log.debug("Bind to Pos data stream")

      // Bind to tables
      PosStream ! BindTable("position", PositionsRepository, implicitly[Deserializer[Pos.PositionRecord]])

      // Track Data Stream states
      PosStream ! SubscribeTransitionCallBack(self)

    case Binded -> Online =>
      log.debug("Positions goes online")

      // Unsubscribe from updates
      PosStream ! UnsubscribeTransitionCallBack(self)

      // Subscribe for sessions snapshots
      PositionsRepository ! SubscribeSnapshots(self)
  }

}