package com.ergodicity.core.trade

import akka.actor._
import com.ergodicity.core.{Security, IsinId}
import collection.mutable
import com.ergodicity.cgate.{Reads, WhenUnhandled}
import com.ergodicity.cgate.StreamEvent.{TnCommit, TnBegin}
import com.ergodicity.cgate.scheme.{OptTrade, FutTrade}
import org.joda.time.DateTime
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.core.session.SessionActor.AssignedContents
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.StreamEvent.ClearDeleted
import com.ergodicity.cgate.Protocol._
import com.ergodicity.core.trade.TradesTracking.SubscribeTrades

class TradesTrackingException(message: String) extends RuntimeException(message)

sealed trait TradesTrackingState

object TradesTrackingState {

  case object Tracking extends TradesTrackingState

}

object TradesTracking {

  case class SubscribeTrades(ref: ActorRef, security: Security)

}


class TradesTracking(FutTrade: ActorRef, OptTrade: ActorRef) extends Actor with FSM[TradesTrackingState, AssignedContents] {

  import TradesTrackingState._

  val subscribers = mutable.Map[Security, Seq[ActorRef]]()

  val futuresDispatcher = context.actorOf(Props(new FutTradesDispatcher(self, FutTrade)), "FuturesDispatcher")
  val optionsDispatcher = context.actorOf(Props(new OptTradesDispatcher(self, OptTrade)), "OptionsDispatcher")

  startWith(Tracking, AssignedContents(Set()))

  when(Tracking) {
    case Event(SubscribeTrades(ref, security), assigned) =>
      val updated = subscribers.getOrElseUpdate(security, Seq()) :+ ref
      subscribers(security) = updated
      stay()

    case Event(trade@Trade(_, _, isin, _, _, _, _), assigned) =>
      log.debug("Trade = "+trade)
      val security = assigned ? isin
      security.flatMap(subscribers.get(_)) foreach (_.foreach(_ ! trade))
      stay()

    case Event(AssignedContents(assigned), AssignedContents(old)) =>
      log.debug("Assigned contents; Size = " + assigned.size)
      stay() using AssignedContents(assigned ++ old)
  }

  initialize
}

class FutTradesDispatcher(tradesTracking: ActorRef, stream: ActorRef) extends Actor with ActorLogging with WhenUnhandled {
  override def preStart() {
    stream ! SubscribeStreamEvents(self)
  }

  protected def receive = handleEvents orElse whenUnhandled

  private def handleEvents: Receive = {
    case TnBegin =>

    case TnCommit =>

    case _: ClearDeleted =>

    case StreamData(FutTrade.deal.TABLE_INDEX, data) =>
      val record = implicitly[Reads[FutTrade.deal]] apply data
      if (record.get_replAct() == 0) {
        val tradeId = record.get_id_deal()
        val sessionId = record.get_sess_id()
        val isinId = IsinId(record.get_isin_id())
        val price = record.get_price()
        val amount = record.get_amount()
        val time = new DateTime(record.get_moment())
        val noSystem = record.get_nosystem() == 1
        tradesTracking ! Trade(tradeId, sessionId, isinId, price, amount, time, noSystem)
      }
  }
}

class OptTradesDispatcher(tradesTracking: ActorRef, stream: ActorRef) extends Actor with ActorLogging with WhenUnhandled {
  override def preStart() {
    stream ! SubscribeStreamEvents(self)
  }

  protected def receive = handleEvents orElse whenUnhandled

  private def handleEvents: Receive = {
    case TnBegin =>

    case TnCommit =>

    case _: ClearDeleted =>

    case StreamData(OptTrade.deal.TABLE_INDEX, data) =>
      val record = implicitly[Reads[OptTrade.deal]] apply data
      if (record.get_replAct() == 0) {
        val tradeId = record.get_id_deal()
        val sessionId = record.get_sess_id()
        val isinId = IsinId(record.get_isin_id())
        val price = record.get_price()
        val amount = record.get_amount()
        val time = new DateTime(record.get_moment())
        val noSystem = record.get_nosystem() == 1
        tradesTracking ! Trade(tradeId, sessionId, isinId, price, amount, time, noSystem)
      }
  }
}


