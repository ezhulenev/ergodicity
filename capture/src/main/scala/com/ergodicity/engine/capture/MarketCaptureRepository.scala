package com.ergodicity.engine.capture

import org.slf4j.{Logger, LoggerFactory}
import com.mongodb.casbah.Imports._


class MarketCaptureRepository(database: CaptureDatabase) {
  val log = LoggerFactory.getLogger(classOf[MarketCaptureRepository])

  val mongo = database.db
}

trait RevisionTracker {
  this: {def log: Logger; def mongo: MongoDB} =>

  val Revisions = mongo("Revisions")

  def setRevision(stream: String, table: String, rev: Long) {
    log.trace("Set revision = " + rev + "; [" + stream + ", " + table + "]")
    Revisions.findOne(MongoDBObject("stream" -> stream, "table" -> table)) map {
      obj =>
        Revisions.update(obj, $set("revision" -> rev))
    } getOrElse {
      val revision = MongoDBObject("stream" -> stream, "table" -> table, "revision" -> rev)
      Revisions += revision
    }
  }

  def reset(stream: String) {
    Revisions.remove(MongoDBObject("stream" -> stream))
  }

  def revision(stream: String, table: String): Option[Long] = {
    log.trace("Get revision; Stream = " + stream + "; table = " + table)
    Revisions.findOne(MongoDBObject("stream" -> stream, "table" -> table)).flatMap(_.getAs[Long]("revision"))
  }

}

object StreamRevisionTracker {
  def apply(stream: String)(implicit revisionTracker: RevisionTracker) = new StreamRevisionTracker(revisionTracker, stream)
}

class StreamRevisionTracker(revisionTracker: RevisionTracker, stream: String) {
  def setRevision(table: String, rev: Long) {
    revisionTracker.setRevision(stream, table, rev)
  }
}

class TableRevisionTracker(revisionTracker: RevisionTracker, stream: String, table: String) {
  def setRevision(rev: Long) {
    revisionTracker.setRevision(stream, table, rev)
  }
}