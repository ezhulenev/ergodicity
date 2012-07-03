package com.ergodicity.capture

import com.ergodicity.plaza2.DataStream._
import com.ergodicity.plaza2.scheme.Record
import com.twitter.ostrich.stats.Stats
import com.twitter.finagle.kestrel.Client
import java.util.concurrent.atomic.AtomicReference
import sbinary._
import Operations._
import org.jboss.netty.buffer.ChannelBuffers
import com.ergodicity.marketdb.model.{OrderPayload, TradePayload}
import akka.actor.{Terminated, Props, FSM, Actor}
import com.twitter.util.Future
import com.twitter.concurrent.{Tx, Offer}

sealed trait MarketDbCaptureState

object MarketDbCaptureState {

  case object Idle extends MarketDbCaptureState

  case object InTransaction extends MarketDbCaptureState

}

class MarketDbCapture[T <: Record, M](revisionTracker: StreamRevisionTracker, marketDbBuncher: => MarketDbBuncher[M], initialRevision: Option[Long] = None)
                                     (implicit toMarketDbPayload: T => M) extends Actor with FSM[MarketDbCaptureState, Unit] {

  val revisionBuncher = context.actorOf(Props(new RevisionBuncher(revisionTracker)), "RevisionBuncher")
  val marketBuncher = context.actorOf(Props(marketDbBuncher), "KestrelBuncher")

  // Watch child bunchers
  context.watch(marketBuncher)

  startWith(MarketDbCaptureState.Idle, ())

  when(MarketDbCaptureState.Idle) {
    case Event(DataBegin, _) => log.debug("Begin data"); goto(MarketDbCaptureState.InTransaction)
  }

  when(MarketDbCaptureState.InTransaction) {
    case Event(DataEnd, _) =>
      log.debug("End data");
      marketBuncher ! FlushBunch
      revisionBuncher ! FlushBunch
      goto(MarketDbCaptureState.Idle)

    case Event(DataInserted(table, record: T), _) =>
      Stats.incr(self.path + "/DataInserted")
      revisionBuncher ! BunchRevision(table, record.replRev)
      marketBuncher ! BunchMarketEvent(toMarketDbPayload(record))

      stay()
  }

  whenUnhandled {
    case Event(Terminated(child), _) => throw new MarketCaptureException("Terminated child buncher = " + child)

    case Event(e@DatumDeleted(_, rev), _) => initialRevision.map {
      initialRevision =>
        if (initialRevision < rev)
          log.error("Missed some data! Initial revision = " + initialRevision + "; Got datum deleted rev = " + rev);
    }
    stay()

    case Event(e@DataDeleted(_, replId), _) => throw MarketCaptureException("Unexpected DataDeleted event: " + e);
  }

  initialize
}

case object FlushBunch

sealed trait BuncherState

object BuncherState {

  case object Idle extends BuncherState

  case object Accumulating extends BuncherState

}

case class BunchRevision(table: String, revision: Long)

class RevisionBuncher(revisionTracker: StreamRevisionTracker) extends Actor with FSM[BuncherState, Option[Map[String, Long]]] {

  startWith(BuncherState.Idle, None)

  when(BuncherState.Idle) {
    case Event(BunchRevision(table, revision), None) => goto(BuncherState.Accumulating) using Some(Map(table -> revision))
  }

  when(BuncherState.Accumulating) {
    case Event(BunchRevision(table, revision), None) => stay() using Some(Map(table -> revision))
    case Event(BunchRevision(table, revision), Some(revisions)) => stay() using Some(revisions + (table -> revision))

    case Event(FlushBunch, Some(revisions)) => flushRevision(revisions); goto(BuncherState.Idle) using None
  }

  initialize

  def flushRevision(revisions: Map[String, Long]) {
    log.info("Flush revisions: " + revisions)
    revisions.foreach {
      case (table, revision) => revisionTracker.setRevision(table, revision)
    }
  }
}

case class BunchMarketEvent[T](payload: T)

sealed trait MarketDbBuncher[T] extends Actor with FSM[BuncherState, Option[List[T]]] {
  def client: Client

  def queue: String

  implicit def writes: Writes[List[T]]

  startWith(BuncherState.Idle, None)

  when(BuncherState.Idle) {
    case Event(BunchMarketEvent(payload: T), None) => goto(BuncherState.Accumulating) using Some(List(payload))
  }

  when(BuncherState.Accumulating) {
    case Event(BunchMarketEvent(payload: T), None) => stay() using Some(List(payload))
    case Event(BunchMarketEvent(payload: T), Some(payloads)) =>
      stay() using Some(payload :: payloads)

    case Event(FlushBunch, Some(payloads)) => flushPayloads(payloads); goto(BuncherState.Idle) using None
  }

  initialize

  def flushPayloads(payload: List[T]) {
    log.info("Flush market payloads: " + payload.size)
    val bytes = toByteArray(payload)
    client.write(queue, OfferOnce(ChannelBuffers.wrappedBuffer(bytes))) onSuccess {
      err =>
      // Stop market buncher on Kestrel client failed
        context.stop(self)
    }
  }

  object OfferOnce {
    def apply[A](value: A): Offer[A] = new Offer[A] {
      val ref = new AtomicReference[Option[A]](Some(value))

      def prepare() = ref.getAndSet(None) map {value =>
        Future.value(Tx.const(value))
      } getOrElse Future.never
    }
  }
}

class TradesBuncher(val client: Client, val queue: String) extends MarketDbBuncher[TradePayload] {

  import com.ergodicity.marketdb.model.TradeProtocol._

  val writes = implicitly[Writes[List[TradePayload]]]
}

class OrdersBuncher(val client: Client, val queue: String) extends MarketDbBuncher[OrderPayload] {

  import com.ergodicity.marketdb.model.OrderProtocol._

  val writes = implicitly[Writes[List[OrderPayload]]]
}