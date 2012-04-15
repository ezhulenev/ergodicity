package com.ergodicity.engine.core

import akka.event.Logging
import com.ergodicity.engine.plaza2.Repository.Snapshot
import com.ergodicity.engine.plaza2.scheme.Record
import akka.actor.{Props, ActorRef, Actor}
import model.{Instrument, InstrumentState, Security}

class SessionContents[S <: Security, R <: Record {def isin : String; def state : Long}](val converter: R => S) extends Actor {
  val log = Logging(context.system, this)

  protected[core] var instruments: Map[String, ActorRef] = Map()

  protected def receive = {
    case snapshot: Snapshot[R] =>
      handleRecords(snapshot.data)
  }

  private def handleRecords(records: Iterable[R]) {
    records.foreach {
      record =>
        val state = InstrumentState(record.state)
        val isin = record.isin
        instruments.get(isin).map(_ ! state) getOrElse {
          instruments = instruments + (record.isin -> context.actorOf(Props(new Instrument(converter(record), state)), isin))
        }
    }
  }
}
