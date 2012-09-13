package com.ergodicity.core.broker

import akka.actor.{Status, FSM, ActorRef, Actor}
import akka.util.duration._
import ru.micexrts.cgate.{Publisher => CGPublisher, PublishFlag}
import com.ergodicity.cgate._
import akka.util.Duration
import akka.actor.FSM.Failure
import scala.Some
import com.ergodicity.core.{Market, OrderDirection, OrderType, Isin}
import com.ergodicity.core.broker.Protocol.Protocol
import com.ergodicity.core.broker.Action.{Cancel, AddOrder}
import com.ergodicity.core.broker.ReplyEvent.{ReplyData, TimeoutMessage}
import java.nio.ByteBuffer
import collection.mutable

protected[broker] case class PublisherState(state: State)

object Broker {

  type Decoder = (Int, ByteBuffer) => _

  case class Config(clientCode: String)

  case object Open

  case object Close

  case object Dispose

  case object UpdateState


  def apply(underlying: CGPublisher, updateStateDuration: Option[Duration] = Some(1.second))
           (implicit config: Broker.Config) = new Broker(underlying, updateStateDuration)(config)

  def Buy[M <: Market](isin: Isin, amount: Int, price: BigDecimal, orderType: OrderType)
                      (implicit protocol: Protocol[AddOrder, OrderId, M]): MarketCommand[AddOrder, OrderId, M] = MarketCommand(AddOrder(isin, amount, price, orderType, OrderDirection.Buy))

  def Sell[M <: Market](isin: Isin, amount: Int, price: BigDecimal, orderType: OrderType)
                       (implicit protocol: Protocol[AddOrder, OrderId, M]): MarketCommand[AddOrder, OrderId, M] = MarketCommand(AddOrder(isin, amount, price, orderType, OrderDirection.Sell))

  def Cancel[M <: Market](order: OrderId)(implicit protocol: Protocol[Cancel, Cancelled, M]): MarketCommand[Cancel, Cancelled, M] = MarketCommand(Action.Cancel(order))

  case class OpenTimedOut() extends RuntimeException

  case class BrokerError() extends RuntimeException

  case class Handler(ref: ActorRef, decode: Decoder) {
    def replied(data: ReplyData) {
      try {
        ref ! decode(data.messageId, data.data)
      } catch {
        case err: Throwable => ref ! Status.Failure(err)
      }
    }

    def timedOut() {
      ref ! Status.Failure(new BrokerTimedOutException)
    }
  }

}

class Broker(underlying: CGPublisher, updateStateDuration: Option[Duration] = Some(1.second))
            (implicit val config: Broker.Config) extends Actor with FSM[State, Int] {

  import Broker._

  val InitialMessageId = 1

  val handlers = mutable.Map[Int, Handler]()

  private val statusTracker = updateStateDuration.map {
    duration =>
      context.system.scheduler.schedule(0 milliseconds, duration) {
        self ! UpdateState
      }
  }

  startWith(Closed, InitialMessageId)

  when(Closed) {
    case Event(Open, _) =>
      log.info("Open publisher")
      underlying.open("")
      stay()
  }

  when(Opening, stateTimeout = 3.second) {
    case Event(FSM.StateTimeout, _) => throw new OpenTimedOut
  }

  when(Active) {
    case Event(Close, _) =>
      log.info("Close Publisher")
      underlying.close()
      stay()

    case Event(command: MarketCommand[_, _, _], userId) =>
      log.debug("Execute command: " + command)
      val message = command.encode(underlying)
      message.setUserId(userId)
      underlying.post(message, PublishFlag.NEED_REPLY)
      handlers(userId) = Handler(sender, command.decode _)
      stay() using (userId + 1)
  }

  onTransition {
    case Closed -> Opening => log.info("Opening publisher")
    case _ -> Active => log.info("Publisher opened")
    case _ -> Closed => log.info("Publisher closed")
  }

  whenUnhandled {
    case Event(ReplyEvent.Open, _) => stay()

    case Event(PublisherState(com.ergodicity.cgate.Error), _) => throw new BrokerError

    case Event(PublisherState(state), _) if (state != stateName) => goto(state)

    case Event(PublisherState(state), _) if (state == stateName) => stay()

    case Event(UpdateState, _) =>
      self ! PublisherState(State(underlying.getState))
      stay()

    case Event(Dispose, _) =>
      log.info("Dispose publisher")
      underlying.dispose()
      stop(Failure("Disposed"))

    case Event(TimeoutMessage(id), _) if (handlers contains id) =>
      handlers(id).timedOut()
      handlers.remove(id)
      stay()

    case Event(reply@ReplyData(id, msgId, data), _) if (handlers contains id) =>
      handlers(id).replied(reply)
      handlers.remove(id)
      stay()
  }

  onTermination {
    case StopEvent(reason, s, d) => log.error("Publisher failed, reason = " + reason)
  }

  initialize

  override def postStop() {
    statusTracker.foreach(_.cancel())
    super.postStop()
  }
}