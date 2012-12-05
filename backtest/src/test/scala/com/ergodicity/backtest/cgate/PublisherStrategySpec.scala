package com.ergodicity.backtest.cgate

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit._
import com.ergodicity.backtest.Mocking
import com.ergodicity.backtest.service.OrdersService.ManagedOrder
import com.ergodicity.backtest.service.{SessionContext, OrdersService}
import com.ergodicity.core._
import com.ergodicity.core.broker.OrderId
import com.ergodicity.core.session.InstrumentState
import com.ergodicity.schema.{OptSessContents, FutSessContents, Session}
import org.joda.time.DateTime
import org.mockito.Matchers._
import org.mockito.Mockito
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{WordSpec, BeforeAndAfterAll}

class PublisherStrategySpec extends TestKit(ActorSystem("PublisherStrategySpec", com.ergodicity.engine.EngineSystemConfig)) with WordSpec with ShouldMatchers with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val sessionId = SessionId(100, 100)

  val begin = new DateTime(2012, 1, 1, 10, 0)
  val end = begin.withHourOfDay(20)

  val futureContract = FutureContract(IsinId(100), Isin("FISIN"), ShortIsin("FISINS"), "Future")
  val optionContract = OptionContract(IsinId(101), Isin("OISIN"), ShortIsin("OISINS"), "Option")

  val session = Session(Mocking.mockSession(sessionId.fut, sessionId.opt, begin, end))
  val futures = FutSessContents(Mocking.mockFuture(sessionId.fut, futureContract.id.id, futureContract.isin.isin, futureContract.shortIsin.shortIsin, futureContract.name, 115, InstrumentState.Assigned.toInt)) :: Nil
  val options = OptSessContents(Mocking.mockOption(sessionId.fut, optionContract.id.id, optionContract.isin.isin, optionContract.shortIsin.shortIsin, optionContract.name, 115)) :: Nil

  implicit val sessionContext = SessionContext(session, futures, options)

  "ExecuteOnDeclaredPrice strategy" must {
    "execute order on declared price" in {
      val orders = Mockito.mock(classOf[OrdersService])
      val managedOrder = Mockito.mock(classOf[ManagedOrder])
      Mockito.when(orders.create(any(), any(), any(), any(), any(), any(), any())).thenReturn(managedOrder)

      val strategy = new PublisherStrategy.ExecuteOnDeclaredPrice(orders)

      val buy = broker.Action.AddOrder(futureContract.isin, 1, 100, OrderType.ImmediateOrCancel, OrderDirection.Buy)
      val orderId = strategy.apply(buy)

      assert(orderId == Right(OrderId(1)))

      Mockito.verify(managedOrder).fill(any(), any(), any())
    }

    "fail if Isin not assigned" in {
      val orders = Mockito.mock(classOf[OrdersService])
      val strategy = new PublisherStrategy.ExecuteOnDeclaredPrice(orders)

      val buy = broker.Action.AddOrder(Isin("NoSuchIsin"), 1, 100, OrderType.ImmediateOrCancel, OrderDirection.Buy)
      val orderId = strategy.apply(buy)

      assert(orderId.isLeft)
    }

    "fail cancel any order" in {
      val orders = Mockito.mock(classOf[OrdersService])
      val strategy = new PublisherStrategy.ExecuteOnDeclaredPrice(orders)

      val buy = broker.Action.Cancel(OrderId(1))
      val cancelled = strategy.apply(buy)

      assert(cancelled.isLeft)
    }
  }
}
