package com.ergodicity.core.position

import akka.event.Logging
import akka.actor.{Props, ActorRef, Actor}
import com.ergodicity.plaza2.Repository
import com.ergodicity.plaza2.scheme.Pos.PositionRecord
import com.ergodicity.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import com.ergodicity.core.common.{IsinId, WhenUnhandled}

object Positions {
  def apply = new Positions()

  case class JoinPosRepl(dataStream: ActorRef)

}

case class TrackPosition(isin: IsinId)

class Positions extends Actor with WhenUnhandled {
  val log = Logging(context.system, self)

  protected[position] var positions: Map[IsinId, ActorRef] = Map()

  // Repositories
  protected[position] val positionsRepository = context.actorOf(Props(Repository[PositionRecord]), "PositionsRepository")

  // Subscribe to repository updates
  positionsRepository ! SubscribeSnapshots(self)

  protected def receive = handlePositionsSnapshot orElse trackPosition orElse whenUnhandled

  private def trackPosition: Receive = {
    case TrackPosition(isin) =>
      sender ! (positions.get(isin) getOrElse {
        val position = context.actorOf(Props(new Position(isin)))
        positions = positions + (isin -> position)
        position
      })
  }

  private def handlePositionsSnapshot: Receive = {
    case snapshot@Snapshot(repo, _) if (repo == positionsRepository) =>
      val positionsSnapshot = snapshot.asInstanceOf[Snapshot[PositionRecord]]

      // First terminate old positions
      val (_, terminated) = positions.partition {
        case key =>
          positionsSnapshot.data.find(_.isin_id == key._1.id).isDefined
      }
      terminated.values.foreach(_ ! TerminatePosition)

      // Update existing positions and open new one
      positionsSnapshot.data.foreach {
        case position =>
          val id = IsinId(position.isin_id)
          val data = PositionData(position.open_qty, position.buys_qty, position.sells_qty, position.pos, position.net_volume_rur, position.last_deal_id)
          positions.get(id).map(_ ! UpdatePosition(data)) getOrElse {
            // Start new position actor for given IsinId
            val actor = context.actorOf(Props(new Position(id)), id.id.toString)
            actor ! UpdatePosition(data)
            positions = positions + (id -> actor)
          }
      }
  }
}