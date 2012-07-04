package com.ergodicity.engine

import com.twitter.util.Config
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import com.ergodicity.plaza2.Connection.Connect

case class ConnectionProperties(host: String, port: Int, appName: String) {
  def asConnect = Connect(host, port, appName)
}

trait TradingEngineConfig extends Config[TradingEngine] {

  var host = required[String]("localhost")
  var port = required[Int](4001)
  var applicationName = required[String]("TradingEngine")

  var processMessagesTimeout = required[Int](100)

  var system = required[ActorSystem](ActorSystem("TradingEngine", ConfigWithDetailedLogging))

  def connectionProperties = ConnectionProperties(host.value, port.value, applicationName.value)

  val ConfigWithDetailedLogging = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.actor.debug {
      receive = on
      lifecycle = on
    }
    """)
}