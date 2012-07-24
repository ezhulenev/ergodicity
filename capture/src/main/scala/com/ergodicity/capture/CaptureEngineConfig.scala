package com.ergodicity.capture

import com.twitter.ostrich.admin.config.ServerConfig
import org.slf4j.LoggerFactory
import com.twitter.ostrich.admin.RuntimeEnvironment
import com.mongodb.casbah.TypeImports._
import com.mongodb.casbah.MongoConnection
import com.ergodicity.cgate.config.{ConnectionType, Replication}

sealed trait CaptureDatabase {
  def db: MongoDB
}

case class MongoLocal(database: String) extends CaptureDatabase {
  lazy val db = MongoConnection()(database)
}

case class MongoRemote(address: String, database: String) extends CaptureDatabase {
  lazy val db = MongoConnection(address)(database)
}

case class ReplicationScheme(futInfo: Replication,  optInfo: Replication, ordLog: Replication, futTrade: Replication, optTrade: Replication)

case class KestrelConfig(host: String, port: Int, tradesQueue: String, ordersQueue: String, hostConnectionLimit: Int)


trait CaptureEngineConfig extends ServerConfig[CaptureEngine] {
  val log = LoggerFactory.getLogger(classOf[CaptureEngineConfig])

  def connectionType: ConnectionType

  def replication: ReplicationScheme

  def database: CaptureDatabase

  def kestrel: KestrelConfig

  def apply(runtime: RuntimeEnvironment) = new CaptureEngine(connectionType, replication, database, kestrel)
}