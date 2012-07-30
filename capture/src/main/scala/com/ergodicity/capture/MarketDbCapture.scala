package com.ergodicity.capture

import com.twitter.ostrich.stats.Stats
import com.twitter.finagle.kestrel.Client
import java.util.concurrent.atomic.AtomicReference
import sbinary._
import Operations._
import org.jboss.netty.buffer.ChannelBuffers
import com.ergodicity.marketdb.model.{OrderPayload, TradePayload}
import com.twitter.util.Future
import com.twitter.concurrent.{Tx, Offer}
import com.ergodicity.capture.MarketDbCapture.ConvertToMarketDb
import akka.actor._
import com.ergodicity.cgate.DataStream.BindTable
import scalaz.Validation

sealed trait MarketDbCaptureState

object MarketDbCaptureState {

  case object Idle extends MarketDbCaptureState

  case object InTransaction extends MarketDbCaptureState

}


object MarketDbCapture {

  trait ConvertToMarketDb[T, M] {
    def apply(in: T): Validation[String, M]
  }

}

class MarketDbCapture[T, M](tableIndex: Int, dataStream: ActorRef)(marketDbBuncher: => MarketDbBuncher[M])
                           (implicit read: com.ergodicity.cgate.Reads[T], converts: ConvertToMarketDb[T, M]) extends Actor with FSM[MarketDbCaptureState, Unit] {

  import com.ergodicity.cgate.StreamEvent._

  val marketBuncher = context.actorOf(Props(marketDbBuncher), "KestrelBuncher")

  // Watch child bunchers
  context.watch(marketBuncher)

  // Bind Data Stream
  dataStream ! BindTable(tableIndex, self)

  startWith(MarketDbCaptureState.Idle, ())

  when(MarketDbCaptureState.Idle) {
    case Event(TnBegin, _) => log.debug("Begin data"); goto(MarketDbCaptureState.InTransaction)
  }

  when(MarketDbCaptureState.InTransaction) {
    case Event(TnCommit, _) =>
      log.debug("End data")
      marketBuncher ! FlushBunch
      goto(MarketDbCaptureState.Idle)

    case Event(StreamData(_, data), _) =>
      val record = read(data)
      converts(record).fold(
        error => {
          Stats.incr(self.path + "/DataFailed")
          log.error("Failed convert to MarketDb payload: " + error)
        },
        payload => {
          Stats.incr(self.path + "/DataInserted")
          marketBuncher ! BunchMarketEvent(payload)
        }
      )
      stay()
  }

  whenUnhandled {
    case Event(Terminated(child), _) => throw new MarketCaptureException("Terminated child buncher = " + child)
  }

  initialize
}

case object FlushBunch

sealed trait BuncherState

object BuncherState {

  case object Idle extends BuncherState

  case object Accumulating extends BuncherState

}

case class BunchMarketEvent[T](payload: T)

sealed trait MarketDbBuncher[T] extends Actor with FSM[BuncherState, Option[List[T]]] {
  def client: Client

  def queue: String

  implicit def writes: Writes[List[T]]

  startWith(BuncherState.Idle, None)

  when(BuncherState.Idle) {
    case Event(BunchMarketEvent(payload), None) => goto(BuncherState.Accumulating) using Some(List(payload.asInstanceOf[T]))
  }

  when(BuncherState.Accumulating) {
    case Event(BunchMarketEvent(payload), None) => stay() using Some(List(payload.asInstanceOf[T]))
    case Event(BunchMarketEvent(payload), Some(payloads)) =>
      stay() using Some(payload.asInstanceOf[T] :: payloads)

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

      def prepare() = ref.getAndSet(None) map {
        value =>
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