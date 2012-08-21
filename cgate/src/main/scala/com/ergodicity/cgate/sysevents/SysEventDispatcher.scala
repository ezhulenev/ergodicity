package com.ergodicity.cgate.sysevents

import akka.actor.{Actor, ActorLogging, ActorRef}
import com.ergodicity.cgate.repository.ReplicaExtractor
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.{WhenUnhandled, Reads}

case class SourcedSysEvent(source: ActorRef, event: SysEvent)

object SysEventDispatcher {

  case class SubscribeSysEvents(ref: ActorRef)

}

class SysEventDispatcher[T <: SysEvent.SysEventType](source: ActorRef)(implicit reads: Reads[T], replica: ReplicaExtractor[T]) extends Actor with ActorLogging with WhenUnhandled {

  import SysEventDispatcher._

  var subscribers = Seq[ActorRef]()

  protected def receive = handleStreamEvents orElse handleSubscribes orElse whenUnhandled

  private def handleStreamEvents: Receive = {
    case StreamData(_, data) =>
      val rec = reads(data)
      val repl = replica(rec)

      if (repl.replAct == 0) {
        subscribers.foreach(_ ! SysEvent(rec))
      }
  }

  private def handleSubscribes: Receive = {
    case SubscribeSysEvents(ref) => subscribers = subscribers :+ ref
  }
}