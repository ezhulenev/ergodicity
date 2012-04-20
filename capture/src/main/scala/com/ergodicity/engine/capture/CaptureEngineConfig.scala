package com.ergodicity.engine.capture

import com.twitter.ostrich.admin.config.ServerConfig
import org.slf4j.LoggerFactory
import com.twitter.ostrich.admin.RuntimeEnvironment

trait CaptureEngineConfig extends ServerConfig[CaptureEngine] {
  val log = LoggerFactory.getLogger(classOf[CaptureEngineConfig])

  def connectionProperties: ConnectionProperties

  def scheme: CaptureScheme

  def apply(runtime: RuntimeEnvironment) = new CaptureEngine(connectionProperties, scheme)
}