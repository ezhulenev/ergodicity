package com.ergodicity.cep

import akka.event.Logging
import akka.actor.{ActorRef, Actor}
import collection.mutable.ListBuffer

case class CollectComputations(ref: ActorRef)

trait EventStream[E] extends Actor {
  val log = Logging(context.system, this)

  private val subscribers: ListBuffer[ActorRef] = ListBuffer()
  private val computations: ListBuffer[PartialFunction[E, Any]] = ListBuffer()

  private def handleCommands: Receive = {
    case CollectComputations(ref) => subscribers += ref
  }

  private def handleEvent: Receive = {
    case e: E => computations.foreach {
      f =>
        val computation = f(e)
        subscribers.foreach(_ ! computation)
    }
  }

  private def whenUnhandled: Receive = {
    case e => log.warning("unhandled event " + e)
  }

  protected def receive = handleCommands orElse handleEvent orElse whenUnhandled

  protected def onEvent(computation: PartialFunction[E, Any]) {
    computations += computation
  }
}