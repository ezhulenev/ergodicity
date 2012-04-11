package com.ergodicity.engine.plaza2.futures

import akka.actor.{Props, Actor, ActorRef}
import com.ergodicity.engine.plaza2.scheme.SessionRecord
import com.ergodicity.engine.plaza2.scheme.FutInfo._
import com.ergodicity.engine.plaza2.DataStream.JoinTable
import com.ergodicity.engine.plaza2.Repository
import com.ergodicity.engine.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import akka.event.Logging


sealed trait SessionsState
object SessionsState {
  def apply(dataStream: ActorRef) = new Sessions(dataStream)
}

class Sessions(dataStream: ActorRef) extends Actor {
  val log = Logging(context.system, this)

  val repository = context.actorOf(Props(Repository[SessionRecord]))
  repository ! SubscribeSnapshots(self)
  dataStream ! JoinTable(repository, "session")

  protected def receive = {
    case Snapshot(records:Iterable[SessionRecord]) =>
      log.info("Snapshot: " + records)
      printRecords(records)
  }

  private def printRecords(records: Iterable[SessionRecord]) {
    records foreach {r: SessionRecord =>
      val content = new SessionContent(r)
      log.info("Id: "+r.sessionId+"; State: "+SessionState(r.state)+"; IntClState: "+IntClearingState(r.interClState)+"; Content = "+content)
    }
  }
}