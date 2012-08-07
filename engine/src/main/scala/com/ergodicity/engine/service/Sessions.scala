package com.ergodicity.engine.service

import com.ergodicity.engine._
import akka.actor.{Actor, Props}
import com.ergodicity.core.{Sessions => CoreSessions, WhenUnhandled}
import com.ergodicity.cgate.{BindListener, Listener, DataStreamSubscriber, DataStream}
import akka.event.Logging


case object SessionsService extends Service

trait Sessions {
  engine: AkkaEngine with Connection with CreateListener with FutInfoReplication with OptInfoReplication =>

  val FutInfoStream = context.actorOf(Props(new DataStream), "FutInfoDataStream")
  val OptInfoStream = context.actorOf(Props(new DataStream), "FutInfoDataStream")

  val Sessions = context.actorOf(Props(new CoreSessions(FutInfoStream, OptInfoStream)), "Sessions")
  private[this] val sessionsManager = context.actorOf(Props(new SessionsManager(this)), "ConnectionManager")

  log.info("Register Sessions service")
  registerService(SessionsService, sessionsManager)
}

protected[service] class SessionsManager(engine: Engine with Connection with Sessions with CreateListener with FutInfoReplication with OptInfoReplication) extends Actor with WhenUnhandled {
  val log = Logging(context.system, self)

  import engine._

  // Listeners
  val underlyingFutInfoListener = listener(underlyingConnection, futInfoReplication(), new DataStreamSubscriber(FutInfoStream))
  val futInfoListener = context.actorOf(Props(new Listener(BindListener(underlyingFutInfoListener) to Connection)), "FutInfoListener")

  val underlyingOptInfoListener = listener(underlyingConnection, optInfoReplication(), new DataStreamSubscriber(FutInfoStream))
  val optInfoListener = context.actorOf(Props(new Listener(BindListener(underlyingOptInfoListener) to Connection)), "OptInfoListener")

  protected def receive = null
}
