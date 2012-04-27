package com.ergodicity.engine.capture

import org.slf4j.{Logger, LoggerFactory}
import com.mongodb.casbah.Imports._
import com.ergodicity.engine.plaza2.scheme.{OptInfo, FutInfo}


class MarketCaptureRepository(database: CaptureDatabase) {
  val log = LoggerFactory.getLogger(classOf[MarketCaptureRepository])

  val mongo = database.db
}

class MarketDbRepository(database: CaptureDatabase) extends MarketCaptureRepository(database) with RevisionTracker with SessionTracker with FutSessionContentsTracker with OptSessionContentsTracker

trait SessionTracker {
  this: {def log: Logger; def mongo: MongoDB} =>

  val Sessions = mongo("Sessions")
  Sessions.ensureIndex(MongoDBObject("sessionId" -> 1), "sessionIdIndex", false)
  
  def saveSession(record: FutInfo.SessionRecord) {
    log.trace("Save session = "+record)

    Sessions.findOne(MongoDBObject("sessionId" -> record.sessionId)) map {_ => () /* do nothing */} getOrElse {
      /* create new one */
      val session = convert(record)
      Sessions += session
    }
  }

  def convert(record: FutInfo.SessionRecord) = MongoDBObject(
    "sessionId" -> record.sessionId,
    "begin" -> record.begin,
    "end" -> record.end,
    "optionSessionId" -> record.optionsSessionId,
    "interClBegin" -> record.interClBegin,
    "interClEnd" -> record.interClEnd,
    "eveOn" -> record.eveOn,
    "eveBegin" -> record.eveBegin,
    "eveEnd" -> record.eveEnd,
    "monOn" -> record.monOn,
    "monBegin" -> record.monBegin,
    "monEnd" -> record.monEnd,
    "posTransferBegin" -> record.posTransferBegin,
    "posTransferEnd" -> record.posTransferEnd
  )
}

trait FutSessionContentsTracker {
  this: {def log: Logger; def mongo: MongoDB} =>

  val FutContents = mongo("FutSessionContents")
  FutContents.ensureIndex(MongoDBObject("sessionId" -> 1, "isinId" -> 1, "isin" -> 1), "futSessionContentsIdx", false)

  def saveSessionContents(record: FutInfo.SessContentsRecord) {
    log.trace("Save fut session content = " + record)

    FutContents.findOne(MongoDBObject("sessionId" -> record.sessionId, "isinId" -> record.isinId)) map {
      _ => () /* do nothing */
    } getOrElse {
      /* create new one */
      val contents = convert(record)
      FutContents += contents
    }
  }

  def convert(record: FutInfo.SessContentsRecord) = MongoDBObject(
    "sessionId" -> record.sessionId,
    "isinId" -> record.isinId,
    "shortIsin" -> record.shortIsin,
    "isin" -> record.isin,
    "name" -> record.name,
    "signs" -> record.signs,
    "multileg_type" -> record.multileg_type
  )
}

trait OptSessionContentsTracker {
  this: {def log: Logger; def mongo: MongoDB} =>

  val OptContents = mongo("OptSessionContents")
  OptContents.ensureIndex(MongoDBObject("sessionId" -> 1, "isinId" -> 1, "isin" -> 1), "optSessionContentsIdx", false)

  def saveSessionContents(record: OptInfo.SessContentsRecord) {
    log.trace("Save opt session content = " + record)

    OptContents.findOne(MongoDBObject("sessionId" -> record.sessionId, "isinId" -> record.isinId)) map {
      _ => () /* do nothing */
    } getOrElse {
      /* create new one */
      val contents = convert(record)
      OptContents += contents
    }
  }

  def convert(record: OptInfo.SessContentsRecord) = MongoDBObject(
    "sessionId" -> record.sessionId,
    "isinId" -> record.isinId,
    "shortIsin" -> record.shortIsin,
    "isin" -> record.isin,
    "name" -> record.name,
    "signs" -> record.signs
  )
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