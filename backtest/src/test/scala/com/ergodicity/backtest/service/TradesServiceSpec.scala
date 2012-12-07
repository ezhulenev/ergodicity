package com.ergodicity.backtest.service

import akka.actor.ActorSystem
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.event.Logging
import akka.testkit._
import akka.util.duration._
import com.ergodicity.backtest.Mocking
import com.ergodicity.backtest.cgate.{ConnectionStub, ConnectionStubActor, ListenerBindingStub, ReplicationStreamListenerStubActor}
import com.ergodicity.core._
import com.ergodicity.core.session.InstrumentState
import com.ergodicity.engine.Listener._
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.service.{TradesData, ReplicationConnection, InstrumentData}
import com.ergodicity.engine.underlying._
import com.ergodicity.engine.{ServicesState, ServicesActor, Engine}
import com.ergodicity.marketdb.model.{Security, TradePayload}
import com.ergodicity.schema.{OptSessContents, FutSessContents, Session}
import org.joda.time.DateTime
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import com.ergodicity.marketdb.model
import com.ergodicity.core.trade.TradesTracking.SubscribeTrades
import com.ergodicity.core.trade.Trade

class TradesServiceSpec extends TestKit(ActorSystem("TradesServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  val SystemTrade = false

  override def afterAll() {
    system.shutdown()
  }

  // -- Engine Components
  trait Connections extends UnderlyingConnection {
    self: TestEngine =>

    lazy val underlyingConnection = ConnectionStub wrap connectionStub
  }

  trait Listeners extends FutInfoListener with OptInfoListener with FutTradesListener with OptTradesListener {
    self: TestEngine =>

    lazy val futInfoListener = ListenerBindingStub wrap futInfoListenerStub

    lazy val optInfoListener = ListenerBindingStub wrap optInfoListenerStub

    lazy val futTradesListener = ListenerBindingStub wrap futTradesListenerStub

    lazy val optTradesListener = ListenerBindingStub wrap optTradesListenerStub
  }

  // -- Backtest Engine
  class TestEngine extends Engine with Connections with Listeners {
    self: TestEngine =>

    val ServicesActor = system.deadLetters
    val StrategiesActor = system.deadLetters

    val connectionStub = TestFSMRef(new ConnectionStubActor, "ConnectionStub")

    val futInfoListenerStub = TestFSMRef(new ReplicationStreamListenerStubActor, "FutInfoListenerActor")

    val optInfoListenerStub = TestFSMRef(new ReplicationStreamListenerStubActor, "OptInfoListenerActor")

    val futTradesListenerStub = TestFSMRef(new ReplicationStreamListenerStubActor, "FutTradesListenerActor")

    val optTradesListenerStub = TestFSMRef(new ReplicationStreamListenerStubActor, "OptTradesListenerActor")
  }

  // -- Backtest services
  class TestServices(val engine: TestEngine) extends ServicesActor with ReplicationConnection with InstrumentData with TradesData

  val sessionId = SessionId(100, 100)

  val begin = new DateTime(2012, 1, 1, 10, 0)
  val end = begin.withHourOfDay(20)

  val futureContract = FutureContract(IsinId(100), Isin("FISIN"), ShortIsin("FISINS"), "Future")
  val optionContract = OptionContract(IsinId(101), Isin("OISIN"), ShortIsin("OISINS"), "Option")

  val session = Session(Mocking.mockSession(sessionId.fut, sessionId.opt, begin, end))
  val futures = FutSessContents(Mocking.mockFuture(sessionId.fut, futureContract.id.id, futureContract.isin.isin, futureContract.shortIsin.shortIsin, futureContract.name, 115, InstrumentState.Assigned.toInt)) :: Nil
  val options = OptSessContents(Mocking.mockOption(sessionId.fut, optionContract.id.id, optionContract.isin.isin, optionContract.shortIsin.shortIsin, optionContract.name, 115)) :: Nil

  implicit val sessionContext = SessionContext(session, futures, options)

  implicit val timeout = akka.util.Timeout(1.second)

  "Trades Service" must {
    "dispatch trades from underlying MarketDb" in {
      val engine = TestActorRef(new TestEngine, "Engine")
      val services = TestActorRef(new TestServices(engine.underlyingActor), "Services")

      services ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(services, ServicesState.Idle))

      given("engine's trades data service")
      val tradesData = services.underlyingActor.service(TradesData.TradesData)

      given("assigned session")
      implicit val sessions = new SessionsService(engine.underlyingActor.futInfoListenerStub, engine.underlyingActor.optInfoListenerStub)
      val assigned = sessions.assign(session, futures, options)
      assigned.start()

      when("start services")
      services ! StartServices

      then("all services should start")
      expectMsg(3.seconds, Transition(services, ServicesState.Idle, ServicesState.Starting))
      expectMsg(10.seconds, Transition(services, ServicesState.Starting, ServicesState.Active))

      given("backtest trades service")
      val trades = new TradesService(engine.underlyingActor.futTradesListenerStub, engine.underlyingActor.optTradesListenerStub)

      when("subscribe for trades")
      tradesData ! SubscribeTrades(self, futureContract)
      tradesData ! SubscribeTrades(self, optionContract)

      and("dispatch trades from market db")
      val futureTradeId = 1111l
      val optionTradeId = 1112l

      val futureTradeMoment = new DateTime
      val optionTradeMoment = new DateTime

      val futureTrade = new TradePayload(model.Market("Forts"), Security(futureContract.isin.isin), futureTradeId, 100, 1, futureTradeMoment, SystemTrade)
      val optionTrade = new TradePayload(model.Market("Forts"), Security(optionContract.isin.isin), optionTradeId, 100, 1, optionTradeMoment, SystemTrade)

      trades.dispatch(futureTrade, optionTrade)

      then("should get these trades back")
      val received = receiveWhile(messages = 2) {
        case t@Trade(id, sId, securityId, _, _, _, _) if (id == futureTradeId && sId == sessionId.fut && securityId == futureContract.id) => t
        case t@Trade(id, sId, securityId, _, _, _, _) if (id == optionTradeId && sId == sessionId.opt && securityId == optionContract.id) => t
      }

      log.info("Dispatched trades = "+received)
      assert(received.size == 2)
    }
  }
}