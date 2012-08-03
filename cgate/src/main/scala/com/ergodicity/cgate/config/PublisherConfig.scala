package com.ergodicity.cgate.config

import akka.util.Duration
import java.io.File

sealed trait PublisherConfig {
  def config: String

  def apply(): String = config
}

case class FortsMessages(name: String, timeout: Duration, scheme: File, schemeName: String = "message") extends PublisherConfig {
  if (!scheme.exists()) throw new IllegalStateException("Messages scheme fiel doesn't exists: " + scheme)

  val MsgType = "p2mq"
  val Service = "FORTS_SRV"
  val Category = "FORTS_MSG"

  val config = MsgType + "://" + Service + ";category=" + Category + ";timeout=" + timeout.toMillis + ";scheme=|FILE|" + scheme.getAbsolutePath + "|" + schemeName + ";name=" + name
}