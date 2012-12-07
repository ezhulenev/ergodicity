package com.ergodicity.backtest

import akka.actor.ActorSystem
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.event.Logging
import akka.testkit._
import akka.util.duration._
import com.ergodicity.backtest.service.{OrderBooksService, SessionsService, SessionContext}
import com.ergodicity.core.order.OrderBooksTracking.Snapshots
import com.ergodicity.core.order.OrdersSnapshotActor.OrdersSnapshot
import com.ergodicity.core.session.InstrumentState
import com.ergodicity.core.{Mocking => _, _}
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.{EngineState, ServicesState}
import com.ergodicity.schema.{Session, OptSessContents, FutSessContents}
import org.joda.time.DateTime
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import com.ergodicity.engine.strategy.{CoverAllPositions, StrategiesFactory}
import com.ergodicity.engine.Engine.StartEngine

class BacktestEngineSpec extends TestKit(ActorSystem("BacktestEngineSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  val SystemTrade = false

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

  implicit val timeout = akka.util.Timeout(1.second)

  "Backtest Engine" must {
    "start services & strategies" in {
      given("Backtest Engine with CoverAllPositions Strategy")
      lazy val engine = new BacktestEngine(system) {
        val strategies = CoverAllPositions()
      }
      val engineActor = TestActorRef(engine, "Engine")

      engineActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(engineActor, EngineState.Idle))

      given("assigned session")
      implicit val sessions = new SessionsService(engine.futInfoListenerStub, engine.optInfoListenerStub)
      val assigned = sessions.assign(session, futures, options)
      assigned.start()

      and("empty orderbooks")
      val orderBooks = new OrderBooksService(engine.ordLogListenerStub, engine.futOrderBookListenerStub, engine.optOrderBookListenerStub)
      orderBooks.dispatchSnapshots(Snapshots(OrdersSnapshot(0, new DateTime, Seq.empty), OrdersSnapshot(0, new DateTime, Seq.empty)))

      when("start engine")
      engineActor ! StartEngine

      then("engine should start Services & Strategies")
      expectMsg(3.seconds, Transition(engineActor, EngineState.Idle, EngineState.StartingServices))
      expectMsg(10.seconds, Transition(engineActor, EngineState.StartingServices, EngineState.StartingStrategies))
      expectMsg(3.seconds, Transition(engineActor, EngineState.StartingStrategies, EngineState.Ready))
    }
  }
}