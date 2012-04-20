package com.ergodicity.engine.capture

import com.twitter.ostrich.admin.config.ServerConfig
import org.slf4j.LoggerFactory
import com.twitter.ostrich.admin.RuntimeEnvironment
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx

sealed trait OrientDB {
  def apply(): ODatabaseDocumentTx
}

case class LocalDB(path: String) extends OrientDB {
  def apply() = new ODatabaseDocumentTx("local:" + path)
}

case class MemoryDB(path: String) extends OrientDB {
  def apply() = new ODatabaseDocumentTx("memory:" + path)
}


trait CaptureEngineConfig extends ServerConfig[CaptureEngine] {
  val log = LoggerFactory.getLogger(classOf[CaptureEngineConfig])

  def connectionProperties: ConnectionProperties

  def scheme: CaptureScheme

  def database: OrientDB

  def apply(runtime: RuntimeEnvironment) = new CaptureEngine(connectionProperties, scheme)
}