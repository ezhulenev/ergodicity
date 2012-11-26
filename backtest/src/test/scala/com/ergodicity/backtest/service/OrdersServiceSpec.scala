package com.ergodicity.backtest.service

import akka.actor.ActorSystem
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.event.Logging
import akka.testkit
import akka.testkit._
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.backtest.Mocking
import com.ergodicity.backtest.cgate.{ListenerStubActor, ConnectionStubActor, ListenerDecoratorStub, ConnectionStub}
import com.ergodicity.core._
import com.ergodicity.core.session.InstrumentState
import com.ergodicity.engine.Listener._
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.service.{OrdersData, InstrumentData, ReplicationConnection}
import com.ergodicity.engine.underlying.UnderlyingConnection
import com.ergodicity.engine.{ServicesActor, Engine, ServicesState}
import com.ergodicity.schema.{OptSessContents, FutSessContents, Session}
import org.joda.time.DateTime
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import com.ergodicity.core.order.OrdersSnapshotActor.OrdersSnapshot
import com.ergodicity.core.order.OrderBooksTracking.Snapshots
import com.ergodicity.marketdb.model.{Security, OrderPayload}
import com.ergodicity.marketdb.model
import com.ergodicity.core.order.OrderState

class OrdersServiceSpec extends TestKit(ActorSystem("OrdersServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  val SystemTrade = false

  override def afterAll() {
    system.shutdown()
  }

  trait Connections extends UnderlyingConnection {
    self: TestEngine =>

    lazy val underlyingConnection = ConnectionStub wrap connectionStub
  }

  trait Listeners extends FutInfoListener with OptInfoListener with FutOrderBookListener with OptOrderBookListener with OrdLogListener {
    self: TestEngine =>

    lazy val futInfoListener = ListenerDecoratorStub wrap futInfoListenerStub

    lazy val optInfoListener = ListenerDecoratorStub wrap optInfoListenerStub

    lazy val futOrderbookListener = ListenerDecoratorStub wrap futOrderBookListenerStub

    lazy val optOrderbookListener = ListenerDecoratorStub wrap optOrderBookListenerStub

    lazy val ordLogListener = ListenerDecoratorStub wrap ordLogListenerStub
  }

  class TestEngine(implicit system: ActorSystem) extends Engine with Connections with Listeners {
    self: TestEngine =>

    val connectionStub = TestFSMRef(new ConnectionStubActor, "ConnectionStub")

    val futInfoListenerStub = TestFSMRef(new ListenerStubActor, "FutInfoListenerActor")

    val optInfoListenerStub = TestFSMRef(new ListenerStubActor, "OptInfoListenerActor")

    val futOrderBookListenerStub = TestFSMRef(new ListenerStubActor, "FutOrderBookListener")

    val optOrderBookListenerStub = TestFSMRef(new ListenerStubActor, "OptOrderBookListener")

    val ordLogListenerStub = TestFSMRef(new ListenerStubActor, "OrdLogListenerStub")
  }

  class TestServices(val engine: TestEngine) extends ServicesActor with ReplicationConnection with InstrumentData with OrdersData

  val sessionId = SessionId(100, 100)

  val begin = new DateTime(2012, 1, 1, 10, 0)
  val end = begin.withHourOfDay(20)

  val futureContract = FutureContract(IsinId(100), Isin("FISIN"), ShortIsin("FISINS"), "Future")
  val optionContract = OptionContract(IsinId(101), Isin("OISIN"), ShortIsin("OISINS"), "Option")

  val session = Session(Mocking.mockSession(sessionId.fut, sessionId.opt, begin, end))
  val futures = FutSessContents(Mocking.mockFuture(sessionId.fut, futureContract.id.id, futureContract.isin.isin, futureContract.shortIsin.shortIsin, futureContract.name, 115, InstrumentState.Assigned.toInt)) :: Nil
  val options = OptSessContents(Mocking.mockOption(sessionId.fut, optionContract.id.id, optionContract.isin.isin, optionContract.shortIsin.shortIsin, optionContract.name, 115)) :: Nil

  implicit val sessionContext = SessionContext(session, futures, options)

  implicit val timeout = Timeout(1.second)

  "Trades Service" must {
    "dispatch trades from underlying MarketDb" in {
      val engine = testkit.TestActorRef(new TestEngine, "Engine")
      val services = TestActorRef(new TestServices(engine.underlyingActor), "Services")

      services ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(services, ServicesState.Idle))

      given("trades data service")
      val ordersData = services.underlyingActor.service(OrdersData.OrdersData)

      given("assigned session")
      implicit val sessions = new SessionsService(engine.underlyingActor.futInfoListenerStub, engine.underlyingActor.optInfoListenerStub)
      val assigned = sessions.assign(session, futures, options)
      assigned.start()

      given("orders service")
      val orders = new OrdersService(engine.underlyingActor.ordLogListenerStub, engine.underlyingActor.futOrderBookListenerStub, engine.underlyingActor.optOrderBookListenerStub)
      orders.dispatchSnapshots(Snapshots(OrdersSnapshot(0, new DateTime, Seq.empty), OrdersSnapshot(0, new DateTime, Seq.empty)))

      when("start services")
      services ! StartServices

      then("all services should start")
      expectMsg(3.seconds, Transition(services, ServicesState.Idle, ServicesState.Starting))
      expectMsg(10.seconds, Transition(services, ServicesState.Starting, ServicesState.Active))


      val orderId = 1111l
      val Status = 1
      val AddOrder: Short = 1
      val order = OrderPayload(model.Market("Forts"), Security(futureContract.isin.isin), orderId, new DateTime, Status, AddOrder, OrderDirection.Buy.toShort, 100, 1, 100, None)

      when("dispatch market db order payload")
      orders.dispatch(order)

      then("order actor should be created in Active state")
      Thread.sleep(500)
      val orderActor = system.actorFor("/user/Services/OrdersData/OrderBooks/100/FISIN/1111")
      orderActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(orderActor, OrderState.Active))
    }
  }
}