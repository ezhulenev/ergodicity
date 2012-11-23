package com.ergodicity.backtest.service

import akka.actor.ActorSystem
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.dispatch.Await
import akka.event.Logging
import akka.pattern.ask
import akka.testkit._
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.backtest.Mocking
import com.ergodicity.backtest.cgate.{ConnectionStub, ConnectionStubActor, ListenerDecoratorStub, ListenerStubActor}
import com.ergodicity.core.PositionsTracking._
import com.ergodicity.core._
import com.ergodicity.core.position.PositionActor.CurrentPosition
import com.ergodicity.core.position.PositionActor.PositionTransition
import com.ergodicity.core.position.PositionActor.SubscribePositionUpdates
import com.ergodicity.core.position.{Position, PositionDynamics}
import com.ergodicity.core.session.InstrumentState
import com.ergodicity.engine.Listener.{PosListener, OptInfoListener, FutInfoListener}
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.service.{Portfolio, ReplicationConnection, InstrumentData}
import com.ergodicity.engine.underlying._
import com.ergodicity.engine.{ServicesState, ServicesActor, Engine}
import com.ergodicity.schema.{OptSessContents, FutSessContents, Session}
import org.joda.time.DateTime
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}

class PositionsServiceSpec extends TestKit(ActorSystem("PositionsServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  // -- Engine Components
  trait Connections extends UnderlyingConnection {
    self: TestEngine =>

    lazy val underlyingConnection = ConnectionStub wrap connectionStub
  }

  trait Listeners extends FutInfoListener with OptInfoListener with PosListener {
    self: TestEngine =>

    lazy val futInfoListener = ListenerDecoratorStub wrap futInfoListenerStub

    lazy val optInfoListener = ListenerDecoratorStub wrap optInfoListenerStub

    lazy val posListener = ListenerDecoratorStub wrap posListenerStub
  }

  // -- Backtest Engine
  class TestEngine extends Engine with Connections with Listeners {
    self: TestEngine =>

    val connectionStub = TestFSMRef(new ConnectionStubActor, "ConnectionStub")

    val futInfoListenerStub = TestFSMRef(new ListenerStubActor, "FutInfoListenerActor")

    val optInfoListenerStub = TestFSMRef(new ListenerStubActor, "OptInfoListenerActor")

    val posListenerStub = TestFSMRef(new ListenerStubActor("PosListenerStub"))
  }

  // -- Backtest services
  class TestServices(val engine: TestEngine) extends ServicesActor with ReplicationConnection with InstrumentData with Portfolio

  val sessionId = SessionId(100, 100)

  val begin = new DateTime(2012, 1, 1, 10, 0)
  val end = begin.withHourOfDay(20)

  val futureContract = FutureContract(IsinId(100), Isin("FISIN"), ShortIsin("FISINS"), "Future")

  val session = Session(Mocking.mockSession(sessionId.fut, sessionId.opt, begin, end))
  val futures = FutSessContents(Mocking.mockFuture(sessionId.fut, futureContract.id.id, futureContract.isin.isin, futureContract.shortIsin.shortIsin, futureContract.name, 115, InstrumentState.Assigned.toInt)) :: Nil
  val options = OptSessContents(Mocking.mockOption(sessionId.fut, 101, "OISIN", "OSISIN", "Option", 115)) :: Nil

  implicit val timeout = Timeout(1.second)

  "Positions Service" must {
    "support position lifecycle" in {
      val engine = TestActorRef(new TestEngine, "Engine")
      val services = TestActorRef(new TestServices(engine.underlyingActor), "Services")

      services ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(services, ServicesState.Idle))

      given("portfolio service")
      val portfolio = services.underlyingActor.service(Portfolio.Portfolio)

      given("assigned session")
      implicit val sessions = new SessionsService(engine.underlyingActor.futInfoListenerStub, engine.underlyingActor.optInfoListenerStub)
      val assigned = sessions.assign(session, futures, options)
      assigned.start()

      when("start services")
      services ! StartServices

      then("all services should start")
      expectMsg(3.seconds, Transition(services, ServicesState.Idle, ServicesState.Starting))
      expectMsg(10.seconds, Transition(services, ServicesState.Starting, ServicesState.Active))

      and("initial positions should be empty")
      val initialPositions = Await.result((portfolio ? GetPositions).mapTo[Positions], 1.second)
      assert(initialPositions.positions.size == 0)

      val trackedPosition = Await.result((portfolio ? GetTrackedPosition(futureContract)).mapTo[TrackedPosition], 1.second)
      trackedPosition.positionActor ! SubscribePositionUpdates(self)

      expectMsg(CurrentPosition(futureContract, Position.flat, PositionDynamics.empty))

      val positions = new PositionsService(engine.underlyingActor.posListenerStub)

      when("bought future")
      val dealId = 1111l
      val dealAmount = 10

      positions.bought(futureContract, dealAmount, dealId)

      then("should receive Transition notification")
      expectMsg(PositionTransition(futureContract, (Position.flat, PositionDynamics.empty), (Position(dealAmount), PositionDynamics(buys = dealAmount, lastDealId = Some(dealId)))))

      when("toggled session")
      val toggled = positions.toggleSession()

      then("should notify with updated dynamics")
      expectMsg(PositionTransition(futureContract, (Position(dealAmount), PositionDynamics(buys = dealAmount, lastDealId = Some(dealId))), (Position(dealAmount), PositionDynamics(open = dealAmount))))

      when("try discard opened position")
      intercept[IllegalStateException] {
        toggled.discard(futureContract)
      }
      then("should fail with Exception")

      when("position covered")
      toggled.sold(futureContract, dealAmount, dealId)

      then("should notify on position update")
      expectMsg(PositionTransition(futureContract, (Position(dealAmount), PositionDynamics(open = dealAmount)), (Position.flat, PositionDynamics(open = dealAmount, sells = dealAmount, lastDealId = Some(dealId)))))

      when("position discarded")
      toggled.discard(futureContract)

      then("do nothing")
    }
  }
}