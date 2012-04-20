package com.ergodicity.engine.capture

import com.orientechnologies.orient.core.record.impl.ODocument
import scala.None
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery
import java.util.ArrayList
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx
import org.slf4j.{Logger, LoggerFactory}
import com.orientechnologies.orient.core.storage.OStorage.CLUSTER_TYPE
import com.orientechnologies.orient.core.command.OCommandRequest
import com.orientechnologies.orient.core.sql.OCommandSQL

class MarketCaptureRepository(orientDb: OrientDB) {
  val log = LoggerFactory.getLogger(classOf[MarketCaptureRepository])

  val db = orientDb()

  if (!db.exists()) {
    db.create()
  } else {
    db.open("admin", "admin")
  }

  def close() {
    db.close()
  }
}

trait RevisionTracking { this: {def log: Logger; def db: ODatabaseDocumentTx} =>

  private val Query = new OSQLSynchQuery[ODocument]("SELECT * FROM Revision where stream = ? and table = ?")

  db.command[OCommandRequest](new OCommandSQL("CREATE CLASS Revision")).execute()
  db.command[OCommandRequest](new OCommandSQL("CREATE CLASS Revision")).execute()


  def setRevision(stream: String, table: String, rev: Long) {
    val doc = find(stream, table) getOrElse {
      val doc = db.newInstance("Revision")
      doc.field("stream", stream);
      doc.field("table", table);
      doc
    }
    doc.field("revision", rev);
    db.save(doc)
  }

  def revision(stream: String, table: String): Option[Long] = {
    find(stream, table).map(_.field[Long]("revision"))
  }

  private def find(stream: String, table: String): Option[ODocument] = {
    val list = db.query(Query, stream, table).asInstanceOf[ArrayList[ODocument]]
    log.info("List: "+list)
    if (list.isEmpty) None else Some(list.get(0))
  }
}