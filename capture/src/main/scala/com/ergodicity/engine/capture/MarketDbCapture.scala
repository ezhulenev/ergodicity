package com.ergodicity.engine.capture

import com.ergodicity.engine.plaza2.DataStream._
import com.ergodicity.engine.plaza2.scheme.Record
import com.twitter.ostrich.stats.Stats
import akka.actor.{Props, FSM, Actor}
import com.twitter.finagle.kestrel.Client
import com.twitter.concurrent.Offer
import java.util.concurrent.atomic.AtomicReference
import sbinary._
import Operations._
import org.jboss.netty.buffer.ChannelBuffers
import com.ergodicity.marketdb.model.{OrderPayload, TradePayload}
import akka.actor.FSM.{CurrentState, Transition, SubscribeTransitionCallBack}

case class MarketDbCaptureException(msg: String) extends RuntimeException(msg)

sealed trait MarketDbCaptureState

object MarketDbCaptureState {

  case object Idle extends MarketDbCaptureState

  case object InTransaction extends MarketDbCaptureState

}

class MarketDbCapture[T <: Record, M](revisionTracker: StreamRevisionTracker, marketDbBuncher: => MarketDbBuncher[M], initialRevision: Option[Long] = None)
                                     (implicit toMarketDbPayload: T => M) extends Actor with FSM[MarketDbCaptureState, Unit] {

  val revisionBuncher = context.actorOf(Props(new RevisionBuncher(revisionTracker)), "RevisionBuncher")
  val marketBuncher = context.actorOf(Props(marketDbBuncher), "KestrelBuncher")

  // Handle when data flushed to MarketDb
  marketBuncher ! SubscribeTransitionCallBack(self)

  startWith(MarketDbCaptureState.Idle, ())

  when(MarketDbCaptureState.Idle) {
    case Event(DataBegin, _) => goto(MarketDbCaptureState.InTransaction)
  }

  when(MarketDbCaptureState.InTransaction) {
    case Event(DataEnd, _) =>
      log.debug("End data");
      marketBuncher ! FlushBunch
      goto(MarketDbCaptureState.Idle)

    case Event(DataInserted(table, record: T), _) =>
      Stats.incr(self.path + "/DataInserted")
      revisionBuncher ! BunchRevision(table, record.replRev)
      marketBuncher ! BunchMarketEvent(toMarketDbPayload(record))

      stay()
  }

  whenUnhandled {
    case Event(e@DatumDeleted(_, rev), _) => initialRevision.map {
      initialRevision =>
        if (initialRevision < rev)
          log.error("Missed some data! Initial revision = " + initialRevision + "; Got datum deleted rev = " + rev);
    }
    stay()

    case Event(e@DataDeleted(_, replId), _) => throw MarketDbCaptureException("Unexpected DataDeleted event: " + e);

    case Event(Transition(ref, BuncherState.Accumulating, BuncherState.Idle), _) if (ref == marketBuncher) =>
      revisionBuncher ! FlushBunch;
      stay()

    // Ignored market buncher state events
    case Event(CurrentState(ref, _), _) if (ref == marketBuncher) => stay()
    case Event(Transition(ref, BuncherState.Idle, BuncherState.Accumulating), _) if (ref == marketBuncher) => stay()
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
    log.info("Flush market payloads: " + payload.size + "; " + payload)
    val bytes = toByteArray(payload)
    client.write(queue, OfferOnce(ChannelBuffers.wrappedBuffer(bytes)))
  }

  object OfferOnce {
    def apply[A](value: A): Offer[A] = new Offer[A] {
      val ref = new AtomicReference[Option[A]](Some(value))

      def objects = Seq()

      def poll() = ref.getAndSet(None).map(() => _)

      def enqueue(setter: this.type#Setter) = null
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