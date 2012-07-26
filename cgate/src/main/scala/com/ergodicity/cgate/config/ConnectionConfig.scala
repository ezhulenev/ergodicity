package com.ergodicity.cgate.config

sealed trait ConnectionConfig {
  def protocol: String

  def host: String

  def port: Int

  def appName: String

  val config = protocol + "://" + host + ":" + port + ";app_name=" + appName

  def apply() = config
}

object ConnectionConfig {

  case class Tcp(host: String, port: Int, appName: String) extends ConnectionConfig {
    def protocol = "p2tcp"
  }

  case class Lrpcq(host: String, port: Int, appName: String) extends ConnectionConfig {
    def protocol = "p2lrpcq"
  }
}