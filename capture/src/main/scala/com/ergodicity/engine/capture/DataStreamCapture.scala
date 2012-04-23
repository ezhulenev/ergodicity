package com.ergodicity.engine.capture

import akka.event.Logging
import com.ergodicity.engine.plaza2.DataStream._
import com.ergodicity.engine.plaza2.scheme.Record
import com.twitter.ostrich.stats.Stats
import akka.util.duration._
import akka.actor.{Props, ActorRef, FSM, Actor}

case class DataStreamCaptureException(msg: String) extends RuntimeException(msg)

case class TrackRevisions(revisionTracker: RevisionTracker, stream: String)

class DataStreamCapture[T <: Record](revision: Option[Long])(capture: T => Any) extends Actor {
  val log = Logging(context.system, this)

  var revisionBuncher: Option[ActorRef] = None

  var count = 0;

  protected def receive = {
    case DataBegin => log.debug("Begin data")
    case e@DatumDeleted(_, rev) => revision.map {
      initialRevision =>
        if (initialRevision < rev)
          log.error("Missed some data! Initial revision = " + initialRevision + "; Got datum deleted rev = " + rev);
    }
    case DataEnd => log.debug("End data")
    case e@DataDeleted(_, replId) => throw DataStreamCaptureException("Unexpected DataDeleted event: " + e)

    case TrackRevisions(tracker, stream) => revisionBuncher = Some(context.actorOf(Props(new RevisionBuncher(tracker, stream)), "RevisionBuncher"))

    case DataInserted(table, record: T) =>
      Stats.incr(self.path + "/DataInserted")
      revisionBuncher.foreach(_ ! PushRevision(table, record.replRev))
      count += 1
      if (count % 1000 == 0) {
        log.info("Inserted record#" + count + ": " + record)
      }
      capture(record)
  }
}


sealed trait RevisionBuncherState

object RevisionBuncherState {

  case object Idle extends RevisionBuncherState

  case object Bunching extends RevisionBuncherState

}


case object FlushRevisions
case class PushRevision(table: String, revision: Long)

class RevisionBuncher(revisionTracker: RevisionTracker, stream: String) extends Actor with FSM[RevisionBuncherState, Option[Map[String, Long]]] {

  startWith(RevisionBuncherState.Idle, None)

  when(RevisionBuncherState.Idle) {
    case Event(PushRevision(table, revision), None) =>
      setTimer("flush", FlushRevisions, 1.second, true)
      goto(RevisionBuncherState.Bunching) using Some(Map(table -> revision))
  }

  when(RevisionBuncherState.Bunching) {
    case Event(PushRevision(table, revision), None) => stay() using Some(Map(table -> revision))
    case Event(PushRevision(table, revision), Some(revisions)) => stay() using Some(revisions + (table -> revision))

    case Event(FlushRevisions, Some(revisions)) =>
      Stats.time("flush_revision") {
        flushRevision(revisions)
      };
      stay() using None
  }

  initialize

  def flushRevision(revisions: Map[String, Long]) {
    log.info("Flush revisions for stream: " + stream + "; " + revisions)
    revisions.foreach {
      case (table, revision) => revisionTracker.setRevision(stream, table, revision)
    }
  }
}