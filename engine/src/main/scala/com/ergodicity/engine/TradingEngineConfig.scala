package com.ergodicity.engine

import com.twitter.util.Config
import akka.actor.ActorSystem
import com.ergodicity.plaza2.Connection.Connect
import java.io.File

case class ConnectionProperties(host: String, port: Int, appName: String) {
  def asConnect = Connect(host, port, appName)
}

trait TradingEngineConfig extends Config[TradingEngine] {

  // Connection Data
  var host = required[String]("localhost")
  var port = required[Int](4001)
  var applicationName = required[String]("TradingEngine")

  var processMessagesTimeout = required[Int](100)

  // Actor System
  var system = required[ActorSystem](ActorSystem("TradingEngine", ConfigWithDetailedLogging))

  // Ini files
  var futInfo = required[File]
  var optInfo = required[File]

  def connectionProperties = ConnectionProperties(host, port, applicationName)
}