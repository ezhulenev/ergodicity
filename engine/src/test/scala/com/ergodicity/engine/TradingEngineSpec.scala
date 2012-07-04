package com.ergodicity.engine

import component.ConnectionComponent
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.plaza2.Connection
import org.mockito.Mockito._
import TradingEngineState._
import plaza2.{ConnectionStatusChanged, Connection => P2Connection}
import akka.actor.{Terminated, ActorSystem}

class TradingEngineSpec extends TestKit(ActorSystem("TradingEngineSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  type StatusHandler = ConnectionStatusChanged => Unit

  override def afterAll() {
    system.shutdown()
  }

  val Host = "host"
  val Port = 4001
  val AppName = "TradingEngineSpec"

  "Trading engine" must {
    "be initialized in Idle state" in {
      val engine = TestFSMRef(new TradingEngine with MockConnectionComponent {
        lazy val underlying = mock(classOf[P2Connection])
      }, "Engine")

      assert(engine.stateName == Idle)
    }

    "connect on StartTradingEngine" in {
      val p2 = mock(classOf[P2Connection])
      val engine = TestFSMRef(new TradingEngine with MockConnectionComponent {
        lazy val underlying = p2
      }, "Engine")

      engine ! StartTradingEngine(ConnectionProperties(Host, Port, AppName))

      // Let message be propagated to Connection actor
      Thread.sleep(100)

      verify(p2).connect()
    }

    "stop on connection terminated" in {
      val engine = TestFSMRef(new TradingEngine with MockConnectionComponent {
        lazy val underlying = mock(classOf[P2Connection])
      }, "Engine")
      val underlying = engine.underlyingActor.asInstanceOf[TradingEngine]

      engine.setState(Connecting)
      watch(engine)

      engine ! Terminated(underlying.Connection)
      expectMsg(Terminated(engine))
    }
  }

  trait MockConnectionComponent extends ConnectionComponent {
    def underlying: P2Connection
    lazy val connectionCreator = Connection(underlying)
  }
}