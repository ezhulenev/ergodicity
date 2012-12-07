package com.ergodicity.backtest.cgate

import akka.actor.{ActorRef, FSM, Actor}
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.duration._
import com.ergodicity.backtest.cgate.PublisherStubActor.Command
import com.ergodicity.backtest.cgate.PublisherStubActor.PublisherContext
import com.ergodicity.backtest.service.SessionContext
import com.ergodicity.backtest.service.{RepliesService, OrdersService}
import com.ergodicity.cgate.scheme.Message
import com.ergodicity.cgate.{Active, Opening, Closed, State}
import com.ergodicity.core.Market.{Options, Futures}
import com.ergodicity.core.broker.Action.AddOrder
import com.ergodicity.core.broker.Action.Cancel
import com.ergodicity.core.broker._
import com.ergodicity.core.{OrderDirection, Isin, OrderType, broker}
import java.nio.ByteBuffer
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import ru.micexrts.cgate.messages.DataMessage
import ru.micexrts.cgate.{Publisher => CGPublisher, PublishFlag, CGateException}
import scala.Left
import scala.Right
import scala.Some
import akka.util.Timeout
import org.joda.time.DateTime

object PublisherStub {

  import PublisherStubActor.Command._

  private[PublisherStub] object DataMessageStub {

    def size(id: Int) = id match {
      case Message.FutAddOrder.MSG_ID => 150
      case Message.FutDelOrder.MSG_ID => 26
      case Message.OptAddOrder.MSG_ID => 150
      case Message.OptDelOrder.MSG_ID => 26
      case _ => throw new IllegalArgumentException("Unsupported message id = " + id)
    }

    def apply(msgId: Int) = {
      val buff = ByteBuffer.allocate(size(msgId))
      var userId = 0

      def setUserId(i: InvocationOnMock) {
        userId = i.getArguments.apply(0).asInstanceOf[Int]
      }

      def getUserId(i: InvocationOnMock) = userId

      val mock = Mockito.mock(classOf[DataMessage])
      doAnswer(setUserId _).when(mock).setUserId(any())
      doAnswer(getUserId _).when(mock).getUserId
      when(mock.getData).thenReturn(buff)
      when(mock.getMsgId).thenReturn(msgId)
      mock
    }
  }

  def wrap(actor: ActorRef) = {
    implicit val timeout = Timeout(1.second)

    def execCmd(cmd: Command)(i: InvocationOnMock) {
      Await.result((actor ? cmd).mapTo[Either[Unit, CGateException]], 1.second) fold(s => s, e => throw e)
    }

    def getState(i: InvocationOnMock) = {
      Await.result((actor ? GetStateCmd).mapTo[Int], 1.second)
    }

    def newMessage(i: InvocationOnMock) = {
      val msgId = i.getArguments.apply(1).asInstanceOf[Int]
      DataMessageStub(msgId)
    }

    def post(i: InvocationOnMock) {
      val msg = i.getArguments.apply(0).asInstanceOf[ru.micexrts.cgate.messages.DataMessage]
      val mode = i.getArguments.apply(1).asInstanceOf[Int]
      actor ! Post(msg, mode)
    }

    val mock = Mockito.mock(classOf[CGPublisher])
    doAnswer(execCmd(OpenCmd) _).when(mock).open(any())
    doAnswer(execCmd(CloseCmd) _).when(mock).close()
    doAnswer(getState _).when(mock).getState
    doAnswer(newMessage _).when(mock).newMessage(any(), any())
    doAnswer(post _).when(mock).post(any(), any())
    mock
  }
}


object PublisherStubActor {

  sealed trait Command

  object Command {

    case object OpenCmd extends Command

    case object CloseCmd extends Command

    case object GetStateCmd extends Command

    case class Post(message: ru.micexrts.cgate.messages.DataMessage, mode: Int)

  }

  case class PublisherContext(strategy: PublisherStrategy, futures: RepliesService[Futures], options: RepliesService[Options])(implicit val sessionContext: SessionContext)

}

trait PublisherStrategy {
  type ActionReaction = PartialFunction[broker.Action[_], Either[BrokerException, Reaction]]

  import com.ergodicity.core.broker

  def apply[R <: Reaction](action: broker.Action[R]): Either[BrokerException, R]
}

object PublisherStrategy {

  class ExecuteOnDeclaredPrice(orders: OrdersService, startOrderId: Long = 1, startDealId: Long = 1)(implicit context: SessionContext) extends PublisherStrategy {
    var currentOrderId = startOrderId
    var currentDealId = startDealId

    def apply[R <: Reaction](action: Action[R]) = action match {
      case broker.Action.AddOrder(isin, amount, price, orderType, direction) if (context.assigned(isin)) =>
        val (orderId, dealId) = (getOrderId, getDealId)
        val order = orders.create(orderId, direction, isin, amount, price, orderType, new DateTime)
        order.fill(new DateTime, amount, (dealId, price))
        Right(OrderId(orderId).asInstanceOf[R])

      case broker.Action.AddOrder(isin, _, _, _, _) =>
        Left(ActionFailedException(1, "Isin not assigned for session; Isin = " + isin))

      case broker.Action.Cancel(OrderId(id)) =>
        Left(ActionFailedException(1, "No order with id = " + id))
    }

    def getOrderId = {
      val next = currentOrderId
      currentOrderId += 1
      next
    }

    def getDealId = {
      val next = currentDealId
      currentDealId += 1
      next
    }
  }

}

class PublisherStubActor extends Actor with FSM[State, Option[PublisherContext]] {

  import PublisherStubActor.Command._

  startWith(Closed, None)

  when(Closed) {
    case Event(OpenCmd, _) => goto(Opening) replying (Left(()))

    case Event(CloseCmd, _) => stay() replying (Right(new CGateException("Publisher already closed")))
  }

  when(Opening, stateTimeout = 50.millis) {
    case Event(CloseCmd, _) => goto(Closed) replying (Left(()))

    case Event(OpenCmd, _) => stay() replying (Right(new CGateException("Publisher already opened")))

    case Event(FSM.StateTimeout, _) => goto(Active)
  }

  when(Active) {
    case Event(CloseCmd, _) => goto(Closed) replying (Left(()))

    case Event(OpenCmd, _) => stay() replying (Right(new CGateException("Publisher already opened")))
  }

  whenUnhandled {
    case Event(GetStateCmd, _) => stay() replying (stateName.value)

    case Event(context: PublisherContext, _) => stay() using Some(context)

    case Event(Post(message, PublishFlag.NEED_REPLY), Some(publisherContext)) if (message.getMsgId == Message.FutAddOrder.MSG_ID) =>
      val userId = message.getUserId
      val addOrder = new Message.FutAddOrder(message.getData)
      val (isin, orderType, direction) = (Isin(addOrder.get_isin()), OrderType(addOrder.get_type()), OrderDirection(addOrder.get_dir()))

      import publisherContext._
      val reaction = strategy.apply(AddOrder(isin, addOrder.get_amount(), BigDecimal(addOrder.get_price()), orderType, direction))
      reaction.fold(e => futures.fail[OrderId](userId, e), order => futures.reply(userId, order))

      stay()

    case Event(Post(message, PublishFlag.NEED_REPLY), Some(publisherContext)) if (message.getMsgId == Message.FutDelOrder.MSG_ID) =>
      val userId = message.getUserId
      val delOrder = new Message.FutDelOrder(message.getData)
      val orderId = delOrder.get_order_id()

      import publisherContext._
      val reaction = strategy.apply(Cancel(OrderId(orderId)))
      reaction.fold(e => futures.fail[Cancelled](userId, e), order => futures.reply(userId, order))

      stay()

    case Event(Post(message, PublishFlag.NEED_REPLY), Some(publisherContext)) if (message.getMsgId == Message.OptAddOrder.MSG_ID) =>
      val userId = message.getUserId
      val addOrder = new Message.OptAddOrder(message.getData)
      val (isin, orderType, direction) = (Isin(addOrder.get_isin()), OrderType(addOrder.get_type()), OrderDirection(addOrder.get_dir()))

      import publisherContext._
      val reaction = strategy.apply(AddOrder(isin, addOrder.get_amount(), BigDecimal(addOrder.get_price()), orderType, direction))
      reaction.fold(e => options.fail[OrderId](userId, e), order => options.reply(userId, order))

      stay()

    case Event(Post(message, PublishFlag.NEED_REPLY), Some(publisherContext)) if (message.getMsgId == Message.OptDelOrder.MSG_ID) =>
      val userId = message.getUserId
      val delOrder = new Message.OptDelOrder(message.getData)
      val orderId = delOrder.get_order_id()

      import publisherContext._
      val reaction = strategy.apply(Cancel(OrderId(orderId)))
      reaction.fold(e => options.fail[Cancelled](userId, e), order => options.reply(userId, order))

      stay()
  }
}

