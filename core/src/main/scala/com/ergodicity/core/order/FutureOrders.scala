package com.ergodicity.core.order

import akka.event.Logging
import com.ergodicity.core.common.WhenUnhandled
import akka.actor.{Kill, Props, ActorRef, Actor}

class FutureOrders extends Actor with WhenUnhandled {
  val log = Logging(context.system, self)

  protected[order] var sessions: Map[Int, ActorRef] = Map()

  protected def receive = bindSessionOrders orElse dropSessOrders orElse whenUnhandled

  private def bindSessionOrders: Receive = {
    case BindSessionOrders(sessionId) =>
      val orders = sessions.get(sessionId) getOrElse {
        val actor = context.actorOf(Props(new FutureSessionOrders(sessionId)), "Session#" + sessionId)
        context.watch(actor)
        sessions = sessions + (sessionId -> actor)
        actor
      }
      sender ! orders
  }

  private def dropSessOrders: Receive = {
    case DropSessionOrders(sessionId) =>
      sessions.get(sessionId).map(_ ! Kill)
      sessions = sessions - sessionId
  }
}

class FutureSessionOrders(sessionId: Int) extends Actor {
  protected def receive = null
}