package com.ergodicity.cgate.config

sealed trait ConnectionType {
  def protocol: String

  def host: String

  def port: Int

  def appName: String

  val config = protocol + "://" + host + ":" + port + ";app_name=" + appName

  def apply() = config
}

object ConnectionType {

  case class Tcp(host: String, port: Int, appName: String) extends ConnectionType {
    def protocol = "p2tcp"
  }

  case class Lrpcq(host: String, port: Int, appName: String) extends ConnectionType {
    def protocol = "p2lrpcq"
  }
}