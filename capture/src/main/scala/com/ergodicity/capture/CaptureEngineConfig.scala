package com.ergodicity.capture

import com.twitter.ostrich.admin.config.ServerConfig
import org.slf4j.LoggerFactory
import com.twitter.ostrich.admin.RuntimeEnvironment
import com.mongodb.casbah.TypeImports._
import com.mongodb.casbah.MongoConnection
import java.net.{ConnectException, Socket}
import com.twitter.finagle.kestrel.protocol.{Kestrel => KestrelProtocol}
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.kestrel.Client
import com.ergodicity.cgate.config.{CGateConfig, ConnectionConfig, Replication}

sealed trait CaptureDatabase {
  def apply(): MongoDB
}

case class MongoLocal(database: String) extends CaptureDatabase {
  lazy val db = MongoConnection()(database)

  def apply() = db
}

case class MongoRemote(address: String, database: String) extends CaptureDatabase {
  lazy val db = MongoConnection(address)(database)

  def apply() = db
}

case class ReplicationScheme(futInfo: Replication, optInfo: Replication, ordLog: Replication, futTrade: Replication, optTrade: Replication)

trait KestrelConfig {
  def apply(): Client

  def tradesQueue: String

  def ordersQueue: String
}

case class Kestrel(host: String, port: Int, tradesQueue: String, ordersQueue: String, hostConnectionLimit: Int) extends KestrelConfig {

  lazy val client = Client(ClientBuilder()
    .codec(KestrelProtocol())
    .hosts(host + ":" + port)
    .hostConnectionLimit(hostConnectionLimit)
    .buildFactory())

  def apply() = {
    assertKestrelRunning()
    client
  }

  private def assertKestrelRunning() {
    try {
      new Socket(host, port)
    } catch {
      case e: ConnectException =>
        println("Error: Kestrel must be running on host " + host + "; port " + port)
        throw e
    }
  }
}


trait CaptureEngineConfig extends ServerConfig[CaptureEngine] {
  val log = LoggerFactory.getLogger(classOf[CaptureEngineConfig])

  def cgateConfig: CGateConfig

  def connectionConfig: ConnectionConfig

  def replication: ReplicationScheme

  def database: CaptureDatabase

  def kestrel: KestrelConfig

  def apply(runtime: RuntimeEnvironment) = new CaptureEngine(cgateConfig, connectionConfig, replication, database, kestrel)
}