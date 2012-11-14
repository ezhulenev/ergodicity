package com.ergodicity.backtest

import akka.actor.{ActorLogging, Actor}
import com.ergodicity.cgate.WhenUnhandled
import org.joda.time.Interval
import com.ergodicity.schema.Session
import akka.dispatch.{Future, ExecutionContext}
import com.ergodicity.backtest.MarketRepository.Sessions
import com.ergodicity.backtest.MarketRepository.ScanSessions
import akka.pattern.pipe
import org.squeryl.PrimitiveTypeMode._

object MarketRepository {

  case class ScanSessions(interval: Interval)

  case class Sessions(sessions: Seq[Session])

}

class MarketRepositoryActor(implicit databaseExecutionContext: ExecutionContext) extends Actor with ActorLogging with WhenUnhandled {

  import com.ergodicity.schema.ErgodicitySchema._

  protected def receive = scan orElse whenUnhandled

  private def scan: Receive = {
    case ScanSessions(interval) =>
      log.debug("Scan sessions for interval = " + interval)
      lazy val seq = inTransaction {
        from(sessions)(s => where((s.begin gte interval.getStart.getMillis) and (s.end lte interval.getEnd.getMillis)) select (s)).iterator.toSeq
      }
      Future(Sessions(seq)) pipeTo sender
  }
}