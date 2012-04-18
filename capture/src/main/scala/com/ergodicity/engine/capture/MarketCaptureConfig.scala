package com.ergodicity.engine.capture

import com.twitter.ostrich.admin.config.ServerConfig
import org.slf4j.LoggerFactory
import com.twitter.ostrich.admin.RuntimeEnvironment

trait MarketCaptureConfig extends ServerConfig[MarketCapture] {
  val log = LoggerFactory.getLogger(classOf[MarketCaptureConfig])

  def connectionProperties: ConnectionProperties

  def apply(runtime: RuntimeEnvironment) = new MarketCapture(connectionProperties)
}