package com.ergodicity.backtest.engine

import akka.actor.ActorSystem
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.event.Logging
import akka.testkit._
import com.ergodicity.backtest.cgate.{ConnectionStub, ConnectionStubActor}
import com.ergodicity.cgate.Active
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.service.{TradingConnection, ReplicationConnection}
import com.ergodicity.engine.underlying._
import com.ergodicity.engine.{ServicesState, ServicesActor, Engine}
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}

class ConnectionServiceSpec extends TestKit(ActorSystem("ConnectionServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  // -- Engine Components
  trait Connections extends UnderlyingConnection with UnderlyingTradingConnections {
    self: TestEngine =>

    lazy val underlyingConnection = ConnectionStub wrap (replicationConnectionStub)

    lazy val underlyingTradingConnection = ConnectionStub wrap (tradingConnectionStub)
  }

  class TestEngine extends Engine with Connections {
    self: TestEngine =>

    val replicationConnectionStub = TestFSMRef(new ConnectionStubActor, "ReplicationConStub")
    val tradingConnectionStub = TestFSMRef(new ConnectionStubActor, "TradingConnStub")

    val ServicesActor = system.deadLetters
    val StrategiesActor = system.deadLetters
  }

  // -- Backtest services
  class TestServices(val engine: TestEngine) extends ServicesActor with ReplicationConnection with TradingConnection

  "Test Engine" must {
    "start both connection services" in {
      val engine = TestActorRef(new TestEngine, "Engine")
      val services = TestFSMRef(new TestServices(engine.underlyingActor), "Services")

      services ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(services, ServicesState.Idle))

      services ! StartServices

      expectMsg(Transition(services, ServicesState.Idle, ServicesState.Starting))
      expectMsg(Transition(services, ServicesState.Starting, ServicesState.Active))

      assert(engine.underlyingActor.replicationConnectionStub.stateName == Active)
      assert(engine.underlyingActor.tradingConnectionStub.stateName == Active)
    }
  }
}