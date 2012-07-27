package com.ergodicity.engine.service

import com.ergodicity.engine.Engine
import akka.actor.Props
import com.ergodicity.core.{Sessions => CoreSessions}
import com.ergodicity.cgate.DataStream

case object SessionsService extends Service

trait Sessions {
  engine: Engine with Connection =>

  val FutInfoStream = context.actorOf(Props(new DataStream), "FutInfoDataStream")
  val OptInfoStream = context.actorOf(Props(new DataStream), "FutInfoDataStream")

  val Sessions = context.actorOf(Props(new CoreSessions(FutInfoStream, OptInfoStream)), "Sessions")

  registerService(SessionsService)
}