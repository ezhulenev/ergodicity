package com.ergodicity.capture

import com.twitter.ostrich.admin.config.ServerConfig
import org.slf4j.LoggerFactory
import com.twitter.ostrich.admin.RuntimeEnvironment
import com.mongodb.casbah.TypeImports._
import com.mongodb.casbah.MongoConnection

sealed trait CaptureDatabase {
  def db: MongoDB
}

case class MongoDefault(database: String) extends CaptureDatabase {
  lazy val db = MongoConnection()(database)
}

case class Plaza2Scheme(futInfo: String, optInfo: String, ordLog: String, futTrade: String, optTrade: String)

case class ConnectionProperties(host: String, port: Int, appName: String)

case class KestrelConfig(host: String, port: Int, tradesQueue: String, ordersQueue: String, hostConnectionLimit: Int)


trait CaptureEngineConfig extends ServerConfig[CaptureEngine] {
  val log = LoggerFactory.getLogger(classOf[CaptureEngineConfig])

  def connectionProperties: ConnectionProperties

  def scheme: Plaza2Scheme

  def database: CaptureDatabase

  def kestrel: KestrelConfig

  def apply(runtime: RuntimeEnvironment) = new CaptureEngine(connectionProperties, scheme, database, kestrel)
}