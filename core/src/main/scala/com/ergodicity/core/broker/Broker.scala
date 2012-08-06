package com.ergodicity.core.broker

import akka.actor.{FSM, ActorRef, Actor}
import akka.util.duration._
import akka.pattern.ask
import akka.dispatch.Future
import ru.micexrts.cgate.{Publisher => CGPublisher, PublishFlag}
import com.ergodicity.cgate._
import akka.util.{Duration, Timeout}
import com.ergodicity.cgate.Connection.Execute
import akka.actor.FSM.Failure
import scala.Some
import com.ergodicity.core.{Market, OrderDirection, OrderType, Isin}
import com.ergodicity.core.broker.Protocol.Protocol
import com.ergodicity.core.broker.Action.{Cancel, AddOrder}
import com.ergodicity.core.broker.ReplyEvent.{ReplyData, TimeoutMessage}
import java.nio.ByteBuffer

protected[broker] case class PublisherState(state: State)

object Broker {

  type Decoder = (Int, ByteBuffer) => Either[ActionFailed, _]

  case class Config(clientCode: String)

  case object Open

  case object Close

  case object Dispose


  def apply(withPublisher: WithPublisher, updateStateDuration: Option[Duration] = Some(1.second))
           (implicit config: Broker.Config) = new Broker(withPublisher, updateStateDuration)(config)

  def Buy[M <: Market](isin: Isin, amount: Int, price: BigDecimal, orderType: OrderType)
                      (implicit protocol: Protocol[AddOrder, Order, M]): MarketCommand[AddOrder, Order, M] = MarketCommand(AddOrder(isin, amount, price, orderType, OrderDirection.Buy))

  def Sell[M <: Market](isin: Isin, amount: Int, price: BigDecimal, orderType: OrderType)
                       (implicit protocol: Protocol[AddOrder, Order, M]): MarketCommand[AddOrder, Order, M] = MarketCommand(AddOrder(isin, amount, price, orderType, OrderDirection.Sell))

  def Cancel[M <: Market](order: Order)(implicit protocol: Protocol[Cancel, Cancelled, M]): MarketCommand[Cancel, Cancelled, M] = MarketCommand(Action.Cancel(order))

}

class Broker(withPublisher: WithPublisher, updateStateDuration: Option[Duration] = Some(1.second))
            (implicit val config: Broker.Config) extends Actor with FSM[State, Map[Int, (ActorRef, Broker.Decoder)]] {

  import Broker._

  val InitialId = 1

  private val statusTracker = updateStateDuration.map {
    duration =>
      context.system.scheduler.schedule(0 milliseconds, duration) {
        withPublisher(publisher => publisher.getState) onSuccess {
          case state => self ! PublisherState(State(state))
        }
      }
  }

  startWith(Closed, Map())

  when(Closed) {
    case Event(Open, _) =>
      log.info("Open publisher")
      withPublisher(_.open(""))
      stay()
  }

  when(Opening, stateTimeout = 3.second) {
    case Event(FSM.StateTimeout, _) => stop(Failure("Opening timeout"))
  }

  when(Active) {
    case Event(Close, _) =>
      log.info("Close Publisher")
      withPublisher(_.close())
      stay()

    case Event(command: MarketCommand[_, _, _], pending) =>
      log.debug("Execute command: " + command)
      val counter = if (pending.isEmpty) InitialId else pending.keys.max + 1
      withPublisher(publisher => {
        val message = command.encode(publisher)
        message.setUserId(counter)
        publisher.post(message, PublishFlag.NEED_REPLY)
      })

      stay() using pending + (counter ->(sender, command.decode _))
  }

  onTransition {
    case Closed -> Opening => log.info("Opening publisher")
    case _ -> Active => log.info("Publisher opened")
    case _ -> Closed => log.info("Publisher closed")
  }

  whenUnhandled {
    case Event(PublisherState(com.ergodicity.cgate.Error), _) => stop(Failure("Publisher in Error state"))

    case Event(PublisherState(state), _) if (state != stateName) => goto(state)

    case Event(PublisherState(state), _) if (state == stateName) => stay()

    case Event(Dispose, _) =>
      log.info("Dispose publisher")
      withPublisher(_.dispose())
      stop(Failure("Disposed"))

    case Event(TimeoutMessage(id), pending) if (pending.contains(id)) =>
      val (replyTo, _) = pending(id)
      replyTo ! Left(TimedOut)
      stay() using pending - id

    case Event(ReplyData(id, msgId, data), pending) if (pending.contains(id)) =>
      val (replyTo, decode) = pending(id)
      replyTo ! decode(msgId, data)
      stay() using pending - id
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

trait WithPublisher {
  def apply[T](f: CGPublisher => T)(implicit m: Manifest[T]): Future[T]
}

object BindPublisher {
  implicit val timeout = Timeout(1.second)

  def apply(publisher: CGPublisher) = new {
    def to(connection: ActorRef) = new WithPublisher {
      def apply[T](f: (CGPublisher) => T)(implicit m: Manifest[T]) = (connection ? Execute(_ => f(publisher))).mapTo[T]
    }
  }
}
