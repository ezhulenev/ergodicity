package com.ergodicity.core

import akka.actor._
import collection.mutable
import com.ergodicity.cgate.scheme.Pos
import com.ergodicity.cgate.DataStream._
import akka.dispatch.Future
import akka.util.duration._
import com.ergodicity.cgate.{Reads, WhenUnhandled}
import position.{Position, PositionDynamics, PositionActor}
import position.PositionActor.{GetCurrentPosition, CurrentPosition, UpdatePosition}
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import session.SessionActor.AssignedContents
import scala.Some
import com.ergodicity.cgate.StreamEvent.{StreamData, ClearDeleted, TnCommit, TnBegin}
import com.ergodicity.core.PositionsTracking.{PositionDiscarded, PositionUpdated}
import com.ergodicity.cgate.Protocol._

object PositionsTracking {
  def apply(PosStream: ActorRef) = new PositionsTracking(PosStream)

  case class GetPositionActor(security: Security)

  case class TrackedPosition(security: Security, positionActor: ActorRef)

  case object GetPositions

  case class Positions(positions: Map[Security, Position])

  // Failures

  class PositionsTrackingException(message: String) extends RuntimeException(message)

  // Dispatching
  case class PositionUpdated(id: IsinId, position: Position, dynamics: PositionDynamics)

  case class PositionDiscarded(id: IsinId)

}

sealed trait PositionsTrackingState

object PositionsTrackingState {
  case object Tracking extends PositionsTrackingState
}

class PositionsTracking(PosStream: ActorRef) extends Actor with FSM[PositionsTrackingState, AssignedContents] {

  import PositionsTracking._
  import PositionsTrackingState._

  implicit val timeout = Timeout(1.second)
  implicit val executionContext = context.system

  val positions = mutable.Map[Security, ActorRef]()
  var subscribers = Set[ActorRef]()

  val dispatcher = context.actorOf(Props(new PositionsDispatcher(self, PosStream)), "PositionsDispatcher")

  startWith(Tracking, AssignedContents(Set()))

  when(Tracking) {
    case Event(GetPositions, _) =>
      val currentPositions = Future.sequence(positions.values.map(ref => (ref ? GetCurrentPosition).mapTo[CurrentPosition]))
      currentPositions.map(_.map(_.tuple).toMap) map (Positions(_)) pipeTo sender
      stay()

    case Event(GetPositionActor(security), _) =>
      sender ! TrackedPosition(security, positions.getOrElseUpdate(security, createPosition(security)))
      stay()

    case Event(PositionDiscarded(id), assigned) =>
      val security = assigned ? id
      log.debug("Position discarded; Security = "+security)
      positions.find(_._1 == security) foreach (_._2 ! UpdatePosition(Position.flat, PositionDynamics.empty))
      stay()

    case Event(PositionUpdated(id, position, dynamics), assigned) =>
      val security = assigned ? id
      log.debug("Position updated; Security = "+security)
      val positionActor = positions.getOrElseUpdate(security, createPosition(security))
      positionActor ! UpdatePosition(position, dynamics)
      stay()
  }


  whenUnhandled {
    case Event(assigned: AssignedContents, old) =>
      log.debug("Assigned contents; Size = " + assigned.contents.size)
      stay() using assigned
  }

  private def createPosition(security: Security) = context.actorOf(Props(new PositionActor(security)), security.isin.toActorName)
}

class PositionsDispatcher(positionsTracking: ActorRef, stream: ActorRef) extends Actor with ActorLogging with WhenUnhandled {

  override def preStart() {
    stream ! SubscribeStreamEvents(self)
  }

  protected def receive = handleEvents orElse whenUnhandled

  private def handleEvents: Receive = {
    case TnBegin =>

    case TnCommit =>

    case _: ClearDeleted =>

    case StreamData(Pos.position.TABLE_INDEX, data) =>
      val record = implicitly[Reads[Pos.position]] apply data
      val id = IsinId(record.get_isin_id())

      // Position updated or created
      if (record.get_replAct() == 0) {
        val position = Position(record.get_pos())
        val dynamics = PositionDynamics(
          record.get_open_qty(),
          record.get_buys_qty(),
          record.get_sells_qty(),
          record.get_net_volume_rur(),
          if (record.get_last_deal_id() == 0) None else Some(record.get_last_deal_id())
        )
        positionsTracking ! PositionUpdated(id, position, dynamics)
      } else {
        // Position deleted
        positionsTracking ! PositionDiscarded(id)
      }
  }
}

