package com.ergodicity.engine.strategy

import akka.actor.Actor
import com.ergodicity.engine.Services.ServiceResolver

trait Strategy {
  strategy: Actor =>

  def services: ServiceResolver

}
