package com.ergodicity.engine.capture

import akka.event.Logging
import com.ergodicity.engine.plaza2.DataStream._
import com.ergodicity.engine.plaza2.scheme.Record
import com.twitter.ostrich.stats.Stats
import akka.actor.{Props, ActorRef, FSM, Actor}
import com.twitter.finagle.kestrel.Client
import com.twitter.concurrent.Offer
import java.util.concurrent.atomic.AtomicReference
import sbinary._
import Operations._
import com.ergodicity.marketdb.model.TradeProtocol._
import com.ergodicity.marketdb.model.OrderProtocol._
import org.jboss.netty.buffer.ChannelBuffers
import com.ergodicity.marketdb.model.{OrderPayload, TradePayload}

case class DataStreamCaptureException(msg: String) extends RuntimeException(msg)

case class TrackRevisions(revisionTracker: RevisionTracker, stream: String)

class DataStreamCapture[T <: Record](revision: Option[Long])(capture: T => Any) extends Actor {
  val log = Logging(context.system, this)

  var revisionBuncher: Option[ActorRef] = None

  var count = 0;

  protected def receive = {
    case DataBegin => log.debug("Begin data")
    case e@DatumDeleted(_, rev) => revision.map {
      initialRevision =>
        if (initialRevision < rev)
          log.error("Missed some data! Initial revision = " + initialRevision + "; Got datum deleted rev = " + rev);
    }
    case DataEnd => log.debug("End data")
    case e@DataDeleted(_, replId) => throw DataStreamCaptureException("Unexpected DataDeleted event: " + e)

    case TrackRevisions(tracker, stream) => revisionBuncher = Some(context.actorOf(Props(new RevisionBuncher(tracker, stream)), "RevisionBuncher"))

    case DataInserted(table, record: T) =>
      Stats.incr(self.path + "/DataInserted")
      revisionBuncher.foreach(_ ! BunchRevision(table, record.replRev))
      count += 1
      if (count % 1000 == 0) {
        log.info("Inserted record#" + count + ": " + record)
      }
      capture(record)
  }
}


case object FlushBunch

sealed trait BuncherState

object BuncherState {
  case object Idle extends BuncherState
  case object Accumulating extends BuncherState
}

case class BunchRevision(table: String, revision: Long)

class RevisionBuncher(revisionTracker: RevisionTracker, stream: String) extends Actor with FSM[BuncherState, Option[Map[String, Long]]] {

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
    log.info("Flush revisions for stream: " + stream + "; " + revisions)
    revisions.foreach {
      case (table, revision) => revisionTracker.setRevision(stream, table, revision)
    }
  }
}

case class BunchMarketEvent[T](payload: T)

sealed trait MarketDbBuncher[T] extends Actor with FSM[BuncherState, Option[List[T]]] {
  def client: Client
  def queue: String
  def size: Int

  implicit def writes: Writes[List[T]]

  startWith(BuncherState.Idle, None)

  when(BuncherState.Idle) {
    case Event(BunchMarketEvent(payload: T), None) => goto(BuncherState.Accumulating) using Some(List(payload))
  }

  when(BuncherState.Accumulating) {
    case Event(BunchMarketEvent(payload: T), None) => stay() using Some(List(payload))
    case Event(BunchMarketEvent(payload: T), Some(payloads)) =>
      if (payloads.size == size - 1) self ! FlushBunch;
      stay() using Some(payload :: payloads)

    case Event(FlushBunch, Some(payloads)) => flushPayloads(payloads); goto(BuncherState.Idle) using None
  }

  initialize

  def flushPayloads(payload: List[T]) {
    log.info("Flush market payloads: " + payload)
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
  val size = 100
  import com.ergodicity.marketdb.model.TradeProtocol._
  val writes = implicitly[Writes[List[TradePayload]]]
}

class OrdersBuncher(val client: Client, val queue: String) extends MarketDbBuncher[OrderPayload] {
  val size = 100
  import com.ergodicity.marketdb.model.OrderProtocol._
  val writes = implicitly[Writes[List[OrderPayload]]]
}