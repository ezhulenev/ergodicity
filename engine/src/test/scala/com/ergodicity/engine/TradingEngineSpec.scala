package com.ergodicity.engine

import component.{OptInfoDataStreamComponent, FutInfoDataStreamComponent, ConnectionComponent}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import org.mockito.Mockito._
import TradingEngineState._
import akka.actor.{Terminated, ActorSystem}
import plaza2.{Connection => P2Connection, DataStream => P2DataStream}

class TradingEngineSpec extends TestKit(ActorSystem("TradingEngineSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val Host = "host"
  val Port = 4001
  val AppName = "TradingEngineSpec"

  "Trading engine" must {
    "be initialized in Idle state" in {
      val engine = buildEngine()

      assert(engine.stateName == Idle)
    }

    "connect on StartTradingEngine" in {
      val p2 = mock(classOf[P2Connection])
      val engine = buildEngine(p2)
      engine ! StartTradingEngine(ConnectionProperties(Host, Port, AppName))

      // Let message be propagated to Connection actor
      Thread.sleep(100)

      verify(p2).connect()
    }

    "stop on connection terminated" in {
      val engine = buildEngine()
      val underlying = engine.underlyingActor.asInstanceOf[TradingEngine]

      engine.setState(Connecting)
      watch(engine)

      engine ! Terminated(underlying.Connection)
      expectMsg(Terminated(engine))
    }
  }

  def buildEngine(p2: P2Connection = mock(classOf[P2Connection])) = TestFSMRef(new TradingEngine(100) with ConnectionComponent with FutInfoDataStreamComponent with OptInfoDataStreamComponent {
    lazy val underlyingConnection = p2

    lazy val underlyingFutInfo = mock(classOf[P2DataStream])

    lazy val underlyingOptInfo = mock(classOf[P2DataStream])
  }, "Engine")
}