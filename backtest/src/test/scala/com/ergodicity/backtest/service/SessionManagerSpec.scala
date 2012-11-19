package com.ergodicity.backtest.service

import akka.actor.{ActorRef, Props, ActorSystem}
import akka.event.Logging
import akka.testkit._
import akka.util.duration._
import com.ergodicity.backtest.cgate.{ConnectionStub, ConnectionStubActor, ListenerStub, ListenerStubActor}
import com.ergodicity.cgate.config.ListenerConfig
import com.ergodicity.engine.ReplicationScheme._
import com.ergodicity.engine.service.{ReplicationConnection, InstrumentData}
import com.ergodicity.engine.underlying._
import com.ergodicity.engine.{ServicesState, ServicesActor, Engine}
import org.mockito.Mockito._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{Connection => CGConnection, ISubscriber}
import scala.collection.mutable
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.schema.{OptSessContents, FutSessContents, Session}
import org.joda.time.DateTime
import com.ergodicity.backtest.Mocking
import com.ergodicity.core.SessionId
import com.ergodicity.core.session.{SessionState, InstrumentState}
import com.ergodicity.core.SessionsTracking.{OngoingSession, SubscribeOngoingSessions}

class SessionManagerSpec extends TestKit(ActorSystem("SessionManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  object Replications {
    val OptInfo = mock(classOf[com.ergodicity.cgate.config.Replication])
    val FutInfo = mock(classOf[com.ergodicity.cgate.config.Replication])
  }

  // -- Engine Components
  trait Connections extends UnderlyingConnection {
    self: TestEngine =>

    lazy val underlyingConnection = ConnectionStub wrap connectionStub
  }

  trait Replication extends FutInfoReplication with OptInfoReplication {
    val optInfoReplication = Replications.OptInfo

    val futInfoReplication = Replications.FutInfo
  }

  trait Listener extends UnderlyingListener {
    self: TestEngine =>

    lazy val listenerFactory = new ListenerFactory {
      def apply(connection: CGConnection, config: ListenerConfig, subscriber: ISubscriber) = config match {
        case Replications.OptInfo =>
          listenerActors.getOrElseUpdate(Replications.OptInfo, system.actorOf(Props(new ListenerStubActor(subscriber)), "OptInfoListenerActor"))
          ListenerStub wrap listenerActors(Replications.OptInfo)

        case Replications.FutInfo =>
          listenerActors.getOrElseUpdate(Replications.FutInfo, system.actorOf(Props(new ListenerStubActor(subscriber)), "FutInfoListenerActor"))
          ListenerStub wrap listenerActors(Replications.FutInfo)

        case _ => throw new IllegalArgumentException("Unknown listener config = " + config)
      }
    }
  }

  // -- Backtest Engine
  class TestEngine extends Engine with Connections with Replication with Listener {
    self: TestEngine =>

    val connectionStub = TestFSMRef(new ConnectionStubActor, "ConnectionStub")


    val listenerActors = mutable.Map[com.ergodicity.cgate.config.Replication, ActorRef]()
  }

  // -- Backtest services
  class TestServices(val engine: TestEngine) extends ServicesActor with ReplicationConnection with InstrumentData

  val sessionId = SessionId(100, 100)

  val begin = new DateTime(2012, 1, 1, 10, 0)
  val end = begin.withHourOfDay(20)

  "SessionManager Service" must {
    "assing session" in {
      val engine = TestActorRef(new TestEngine, "Engine")
      val services = TestActorRef(new TestServices(engine.underlyingActor), "Services")

      services ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(services, ServicesState.Idle))

      services ! StartServices

      expectMsg(3.seconds, Transition(services, ServicesState.Idle, ServicesState.Starting))
      expectMsg(10.seconds, Transition(services, ServicesState.Starting, ServicesState.Active))

      val sessions = new SessionsManager(engine.underlyingActor.listenerActors(Replications.FutInfo), engine.underlyingActor.listenerActors(Replications.OptInfo))

      val session = Session(Mocking.mockSession(sessionId.fut, sessionId.opt, begin, end))
      val futures = FutSessContents(Mocking.mockFuture(sessionId.fut, 100, "FISIN", "FSISIN", "Future", 115, InstrumentState.Assigned.toInt)) :: Nil
      val options = OptSessContents(Mocking.mockOption(sessionId.fut, 101, "OISIN", "OSISIN", "Option", 115)) :: Nil

      sessions.assign(session, futures, options)

      val instrumentData = services.underlyingActor.service(InstrumentData.InstrumentData)
      instrumentData ! SubscribeOngoingSessions(self)

      // -- Should track ongoing session
      val ongoing = receiveOne(500.millis).asInstanceOf[OngoingSession]
      assert(ongoing.id == sessionId)

      // -- State should be in Assigned state
      val sessionRef = ongoing.ref
      sessionRef ! SubscribeTransitionCallBack(self)

      expectMsg(CurrentState(sessionRef, SessionState.Assigned))
    }
  }
}