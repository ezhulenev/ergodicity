package com.ergodicity.capture

import com.twitter.ostrich.admin.config.ServerConfig
import org.slf4j.LoggerFactory
import com.twitter.ostrich.admin.RuntimeEnvironment
import java.net.{ConnectException, Socket}
import com.twitter.finagle.kestrel.protocol.{Kestrel => KestrelProtocol}
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.kestrel.Client
import com.ergodicity.cgate.config.{CGateConfig, ConnectionConfig, Replication}
import org.squeryl.{Session => SQRLSession, SessionFactory}

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

  def database: () => SQRLSession

  def kestrel: KestrelConfig

  def apply(runtime: RuntimeEnvironment) = {
    SessionFactory.concreteFactory = Some(database)
    new CaptureEngine(cgateConfig, connectionConfig, replication, kestrel)
  }
}