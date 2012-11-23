package com.ergodicity.backtest.service

import akka.actor.ActorSystem
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.event.Logging
import akka.testkit._
import akka.util.duration._
import com.ergodicity.backtest.Mocking
import com.ergodicity.backtest.cgate.{ConnectionStub, ConnectionStubActor, ListenerDecoratorStub, ListenerStubActor}
import com.ergodicity.core.SessionId
import com.ergodicity.core.SessionsTracking.OngoingSession
import com.ergodicity.core.SessionsTracking.SubscribeOngoingSessions
import com.ergodicity.core.session.{SessionState, InstrumentState}
import com.ergodicity.engine.Listener.{OptInfoListener, FutInfoListener}
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.service.{ReplicationConnection, InstrumentData}
import com.ergodicity.engine.underlying._
import com.ergodicity.engine.{ServicesState, ServicesActor, Engine}
import com.ergodicity.schema.{OptSessContents, FutSessContents, Session}
import org.joda.time.DateTime
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}

class SessionsServiceSpec extends TestKit(ActorSystem("SessionsServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  // -- Engine Components
  trait Connections extends UnderlyingConnection {
    self: TestEngine =>

    lazy val underlyingConnection = ConnectionStub wrap connectionStub
  }

  trait Listeners extends FutInfoListener with OptInfoListener {
    self: TestEngine =>

    lazy val futInfoListener = ListenerDecoratorStub wrap futInfoListenerStub

    lazy val optInfoListener = ListenerDecoratorStub wrap optInfoListenerStub
  }

  // -- Backtest Engine
  class TestEngine extends Engine with Connections with Listeners {
    self: TestEngine =>

    val connectionStub = TestFSMRef(new ConnectionStubActor, "ConnectionStub")

    val futInfoListenerStub = TestFSMRef(new ListenerStubActor, "FutInfoListenerActor")

    val optInfoListenerStub = TestFSMRef(new ListenerStubActor, "OptInfoListenerActor")
  }

  // -- Backtest services
  class TestServices(val engine: TestEngine) extends ServicesActor with ReplicationConnection with InstrumentData

  val sessionId = SessionId(100, 100)

  val begin = new DateTime(2012, 1, 1, 10, 0)
  val end = begin.withHourOfDay(20)

  val session = Session(Mocking.mockSession(sessionId.fut, sessionId.opt, begin, end))
  val futures = FutSessContents(Mocking.mockFuture(sessionId.fut, 100, "FISIN", "FSISIN", "Future", 115, InstrumentState.Assigned.toInt)) :: Nil
  val options = OptSessContents(Mocking.mockOption(sessionId.fut, 101, "OISIN", "OSISIN", "Option", 115)) :: Nil


  "SessionManager Service" must {
    "support normal session lifecycle" in {
      val engine = TestActorRef(new TestEngine, "Engine")
      val services = TestActorRef(new TestServices(engine.underlyingActor), "Services")

      services ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(services, ServicesState.Idle))

      services ! StartServices

      expectMsg(3.seconds, Transition(services, ServicesState.Idle, ServicesState.Starting))
      expectMsg(10.seconds, Transition(services, ServicesState.Starting, ServicesState.Active))

      implicit val sessions = new SessionsService(engine.underlyingActor.futInfoListenerStub, engine.underlyingActor.optInfoListenerStub)

      val instrumentData = services.underlyingActor.service(InstrumentData.InstrumentData)
      instrumentData ! SubscribeOngoingSessions(self)

      when("session assigned")
      val assigned = sessions.assign(session, futures, options)

      then("should receive ongoing session")
      val ongoing = receiveOne(500.millis).asInstanceOf[OngoingSession]
      assert(ongoing.id == sessionId)

      and("it should be in Assigned state")
      val sessionRef = ongoing.ref
      sessionRef ! SubscribeTransitionCallBack(self)

      expectMsg(CurrentState(sessionRef, SessionState.Assigned))

      when("session started")
      val evening = assigned.start()

      then("should receive session transition")
      expectMsg(Transition(sessionRef, SessionState.Assigned, SessionState.Online))

      when("night comes over")
      val suspended = evening.suspend()

      then("session should be suspended")
      expectMsg(Transition(sessionRef, SessionState.Online, SessionState.Suspended))

      when("morning comes over")
      val beforeIntClearing = suspended.resume()

      then("session should be resumed")
      expectMsg(Transition(sessionRef, SessionState.Suspended, SessionState.Online))

      when("intraday clearing started")
      val intradayClearing = beforeIntClearing.startIntradayClearing()

      then("session should be suspended")
      expectMsg(Transition(sessionRef, SessionState.Online, SessionState.Suspended))

      when("intraday clearing finished")
      val afterIntradayClearing = intradayClearing.stopIntradayClearing()

      then("session should be resumed")
      expectMsg(Transition(sessionRef, SessionState.Suspended, SessionState.Online))

      when("clearing started")
      val clearing = afterIntradayClearing.startClearing()

      then("session shoudl be suspended")
      expectMsg(Transition(sessionRef, SessionState.Online, SessionState.Suspended))

      when("clearing completed")
      val completed = clearing.complete()

      then("session should be completed too")
      expectMsg(Transition(sessionRef, SessionState.Suspended, SessionState.Completed))

      assert(completed.id == sessionId)
    }

    "cancel session" in {
      val engine = TestActorRef(new TestEngine, "Engine")
      val services = TestActorRef(new TestServices(engine.underlyingActor), "Services")

      services ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(services, ServicesState.Idle))

      services ! StartServices

      expectMsg(3.seconds, Transition(services, ServicesState.Idle, ServicesState.Starting))
      expectMsg(10.seconds, Transition(services, ServicesState.Starting, ServicesState.Active))

      implicit val sessions = new SessionsService(engine.underlyingActor.futInfoListenerStub, engine.underlyingActor.optInfoListenerStub)

      val instrumentData = services.underlyingActor.service(InstrumentData.InstrumentData)
      instrumentData ! SubscribeOngoingSessions(self)

      when("session assigned")
      val assigned = sessions.assign(session, futures, options)

      then("should receive ongoing session")
      val ongoing = receiveOne(500.millis).asInstanceOf[OngoingSession]
      assert(ongoing.id == sessionId)

      and("it should be in Assigned state")
      val sessionRef = ongoing.ref
      sessionRef ! SubscribeTransitionCallBack(self)

      expectMsg(CurrentState(sessionRef, SessionState.Assigned))

      when("session cancelled")
      val cancelled = assigned.cancel()

      then("should update session state")
      expectMsg(Transition(sessionRef, SessionState.Assigned, SessionState.Canceled))

      assert(cancelled.id == sessionId)
    }
  }
}