package com.ergodicity.engine.capture

import akka.event.Logging
import com.ergodicity.engine.plaza2.DataStream._
import com.ergodicity.engine.plaza2.scheme.Record
import com.twitter.ostrich.stats.Stats
import akka.actor.{FSM, Actor}
import akka.util.duration._

case class DataStreamCaptureException(msg: String) extends RuntimeException(msg)

case class TrackRevisions(revisionTracker: RevisionTracker, stream: String)

class DataStreamCapture[T <: Record](revision: Option[Long])(handler: T => Any) extends Actor {
  val log = Logging(context.system, this)

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

    case DataInserted(_, record: T) =>
      Stats.incr(self.path.name + "@DataInserted")
      count += 1
      if (count % 1000 == 0) {
        log.info("Inserted record#" + count + ": " + record)
      }
      handler(record)
  }
}


sealed trait RevisionBuncherState

object RevisionBuncherState {

  case object Idle extends RevisionBuncherState

  case object Accumulating extends RevisionBuncherState

}


case class PushRevision(table: String, revision: Long)

class RevisionBuncher(revisionTracker: RevisionTracker, stream: String) extends Actor with FSM[RevisionBuncherState, Option[Map[String, Long]]] {

  startWith(RevisionBuncherState.Idle, None)

  when(RevisionBuncherState.Idle) {
    case Event(PushRevision(table, revision), None) => goto(RevisionBuncherState.Accumulating) using Some(Map(table -> revision))
  }

  when(RevisionBuncherState.Accumulating, stateTimeout = 1.seconds) {
    case Event(PushRevision(table, revision), Some(revisions)) => stay() using Some(revisions + (table -> revision))
    case Event(FSM.StateTimeout, Some(revisions)) => flushRevision(revisions); goto(RevisionBuncherState.Idle) using None
  }

  initialize

  def flushRevision(revisions: Map[String, Long]) {
    revisions.foreach {
      case (table, revision) => revisionTracker.setRevision(stream, table, revision)
    }
  }
}