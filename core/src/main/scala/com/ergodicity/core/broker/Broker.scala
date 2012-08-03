package com.ergodicity.core.broker

import com.ergodicity.core.common._
import akka.actor.{FSM, ActorRef, Actor}
import akka.util.duration._
import akka.pattern.ask
import akka.dispatch.Future
import ru.micexrts.cgate.{Publisher => CGPublisher, ErrorCode, MessageType}
import com.ergodicity.cgate._
import ru.micexrts.cgate.messages._
import java.nio.ByteBuffer
import akka.util.{Duration, Timeout}
import com.ergodicity.cgate.Connection.Execute
import akka.actor.FSM.Failure
import scala.Some

protected[broker] sealed trait Order {
  def id: Long
}

case class FutOrder(id: Long) extends Order

case class OptOrder(id: Long) extends Order


protected[broker] sealed trait BrokerCommand

object BrokerCommand {

  case class Sell[S <: Security](sec: S, orderType: OrderType, price: BigDecimal, amount: Int) extends BrokerCommand

  case class Buy[S <: Security](sec: S, orderType: OrderType, price: BigDecimal, amount: Int) extends BrokerCommand

  case class Cancel[O <: Order](order: O) extends BrokerCommand

}

case class ExecutionReport[O <: Order](order: Either[String, O])

case class CancelReport(amount: Either[String, Int])


sealed trait ReplyEvent

object ReplyEvent {

  case class ReplyData(id: Int, messageId: Int, data: ByteBuffer) extends ReplyEvent

  case class TimeoutMessage(id: Int) extends ReplyEvent

  case class UnsupportedMessage(msg: Message) extends ReplyEvent

}

class ReplySubscriber(dataStream: ActorRef) extends Subscriber {

  import ReplyEvent._

  private def decode(msg: Message) = msg.getType match {
    case MessageType.MSG_DATA =>
      val dataMsg = msg.asInstanceOf[DataMessage]
      ReplyData(dataMsg.getUserId, dataMsg.getMsgId, dataMsg.getData)

    case MessageType.MSG_P2MQ_TIMEOUT =>
      val timeoutMsg = msg.asInstanceOf[P2MqTimeOutMessage]
      TimeoutMessage(timeoutMsg.getUserId)

    case _ => UnsupportedMessage(msg)
  }

  def handleMessage(msg: Message) = {
    dataStream ! decode(msg)
    ErrorCode.OK
  }
}

protected[broker] case class PublisherState(state: State)

trait WithPublisher {
  def apply[T](f: CGPublisher => T)(implicit m: Manifest[T]): Future[T]
}

object BindPublisher {
  implicit val timeout = Timeout(1.second)

  def apply(publisher: CGPublisher) = new {
    def to(connection: ActorRef) = new WithPublisher {
      def apply[T](f: (CGPublisher) => T)(implicit m: Manifest[T]) = (connection ? Execute(_ => publisher.synchronized {
        f(publisher)
      })).mapTo[T]
    }
  }
}

object Broker {

  case class Config(clientCode: String)

  case object Open

  case object Close

  case object Dispose


  def apply(withPublisher: WithPublisher, updateStateDuration: Option[Duration] = Some(1.second))
           (implicit config: Broker.Config) = new Broker(withPublisher, updateStateDuration)(config)
}

class Broker(withPublisher: WithPublisher, updateStateDuration: Option[Duration] = Some(1.second))
            (implicit val config: Broker.Config) extends Actor with FSM[State, Map[Int, ActorRef]] {

  import Broker._

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
      val replyTo = sender
      withPublisher(_.open("")) onSuccess {
        case _ => replyTo ! Success
      }
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
  }

  onTransition {
    case Closed -> Opening => log.info("Opening publisher")
    case _ -> Active => log.info("Publisher opened")
    case _ -> Closed => log.info("Publisher closed")
  }

  whenUnhandled {
    case Event(PublisherState(Error), _) => stop(Failure("Publisher in Error state"))

    case Event(PublisherState(state), _) if (state != stateName) => goto(state)

    case Event(PublisherState(state), _) if (state == stateName) => stay()

    case Event(Dispose, _) =>
      log.info("Dispose publisher")
      withPublisher(_.dispose())
      stop(Failure("Disposed"))
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