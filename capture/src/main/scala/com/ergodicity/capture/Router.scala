package com.ergodicity.capture

import akka.actor.{ActorRef, ActorLogging, Actor}
import com.ergodicity.cgate.{WhenUnhandled, StreamEvent}
import com.ergodicity.cgate.StreamEvent._
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.StreamEvent.LifeNumChanged

object Route {
  def apply(target: ActorRef) = new {
    def table(tableIdx: Int): Route = {
      new Route(target, {
        case (TnBegin | TnCommit) => true
        case StreamData(idx, _) if (idx == tableIdx) => true
        case _: LifeNumChanged => true
        case ClearDeleted(idx, _) if (idx == tableIdx) => true
        case _: UnsupportedMessage => true
      })
    }
  }
}

class Route private (target: ActorRef, accept: PartialFunction[StreamEvent, Boolean]) {
  def apply(event: StreamEvent) {
    if ((accept orElse ignore)(event)) target ! event
  }

  private def ignore: PartialFunction[StreamEvent, Boolean] = {
    case _ => false
  }
}

class Router(stream: ActorRef, routes: Seq[Route]) extends Actor with ActorLogging with WhenUnhandled {

  override def preStart() {
    stream ! SubscribeStreamEvents(self)
  }

  protected def receive = receiveStreamEvent orElse whenUnhandled

  private def receiveStreamEvent: Receive = {
    case event: StreamEvent => routes.foreach(_ apply event)
  }
}