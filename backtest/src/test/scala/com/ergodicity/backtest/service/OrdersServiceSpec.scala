package com.ergodicity.backtest.service

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit
import akka.testkit._
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.backtest.Mocking
import com.ergodicity.backtest.cgate._
import com.ergodicity.core._
import com.ergodicity.core.session.InstrumentState
import com.ergodicity.engine.Listener._
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.service.{Trading, OrdersData, InstrumentData, ReplicationConnection}
import com.ergodicity.engine.underlying.{UnderlyingPublisher, UnderlyingConnection}
import com.ergodicity.engine.{ServicesActor, Engine, ServicesState}
import com.ergodicity.schema.{OptSessContents, FutSessContents, Session}
import org.joda.time.DateTime
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.actor.FSM.Transition
import akka.actor.FSM.CurrentState
import com.ergodicity.core.SessionId
import com.ergodicity.core.OptionContract
import com.ergodicity.core.FutureContract
import akka.actor.FSM.SubscribeTransitionCallBack

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

  trait Publisher extends UnderlyingPublisher {
    self: TestEngine =>
    val publisherName = "TestPublisher"

    val brokerCode = "000"

    lazy val underlyingPublisher = PublisherStub wrap publisherStub
  }

  trait Listeners extends FutInfoListener with OptInfoListener with FutOrdersListener with OptOrdersListener with RepliesListener {
    self: TestEngine =>

    lazy val repliesListener = ListenerBindingStub wrap repliesListenerStub

    lazy val futInfoListener = ListenerBindingStub wrap futInfoListenerStub
    lazy val optInfoListener = ListenerBindingStub wrap optInfoListenerStub
    lazy val futOrdersListener = ListenerBindingStub wrap futOrdersListenerStub
    lazy val optOrdersListener = ListenerBindingStub wrap optOrdersListenerStub
  }

  class TestEngine(implicit system: ActorSystem) extends Engine with Connections with Publisher with Listeners {
    self: TestEngine =>

    val connectionStub = TestFSMRef(new ConnectionStubActor, "ConnectionStub")

    // Publisher and replies stream stubs
    val publisherStub = TestFSMRef(new PublisherStubActor, "PublisherStub")
    val repliesListenerStub = TestFSMRef(new ReplyStreamListenerStubActor, "RepliesListenerStub")

    // Replication streams stubs
    val futInfoListenerStub = TestFSMRef(new ReplicationStreamListenerStubActor, "FutInfoListenerStub")
    val optInfoListenerStub = TestFSMRef(new ReplicationStreamListenerStubActor, "OptInfoListenerStub")
    val futOrdersListenerStub = TestFSMRef(new ReplicationStreamListenerStubActor, "FutOrdersStub")
    val optOrdersListenerStub = TestFSMRef(new ReplicationStreamListenerStubActor, "OptOrdersStub")
  }

  class TestServices(val engine: TestEngine) extends ServicesActor with ReplicationConnection with InstrumentData with Trading

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

  "OrderBooks Service" must {
    "dispatch orders from underlying MarketDb" in {
      val engine = testkit.TestActorRef(new TestEngine, "Engine")
      val services = TestActorRef(new TestServices(engine.underlyingActor), "Services")

      services ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(services, ServicesState.Idle))

      given("engine's trading service")
      val trading = services.underlyingActor.service(Trading.Trading)

      given("assigned session")
      implicit val sessions = new SessionsService(engine.underlyingActor.futInfoListenerStub, engine.underlyingActor.optInfoListenerStub)
      val assigned = sessions.assign(session, futures, options)
      assigned.start()

      given("backtest orders service")
      val orders = new OrdersService(engine.underlyingActor.futOrdersListenerStub, engine.underlyingActor.optOrdersListenerStub)

      when("start services")
      services ! StartServices

      then("all services should start")
      expectMsg(3.seconds, Transition(services, ServicesState.Idle, ServicesState.Starting))
      expectMsg(10.seconds, Transition(services, ServicesState.Starting, ServicesState.Active))
    }
  }
}