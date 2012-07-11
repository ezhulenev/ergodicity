package com.ergodicity.engine

import component._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import org.mockito.Mockito._
import TradingEngineState._
import akka.actor.{Terminated, ActorSystem}
import com.ergodicity.plaza2.ConnectionState
import akka.actor.FSM.Transition
import plaza2.{MessageFactory, Connection => P2Connection, DataStream => P2DataStream}

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

      assert(engine.stateName == TradingEngineState.Connecting)
    }

    "stop on connection terminated" in {
      val engine = buildEngine()
      val underlying = engine.underlyingActor.asInstanceOf[TradingEngine]

      engine.setState(Connecting)
      watch(engine)

      engine ! Terminated(underlying.Connection)
      expectMsg(Terminated(engine))
    }

    "goto Initializing state after connection connected" in {
      val engine = buildEngine()
      val underlying = engine.underlyingActor.asInstanceOf[TradingEngine]

      engine.setState(Connecting)

      engine ! Transition(underlying.Connection, ConnectionState.Connecting, ConnectionState.Connected)
      assert(engine.stateName == TradingEngineState.Initializing)
    }
  }

  def buildEngine(conn: P2Connection = mock(classOf[P2Connection]),
                  futInfo: P2DataStream = mock(classOf[P2DataStream]),
                  optInfo: P2DataStream = mock(classOf[P2DataStream]),
                  pos: P2DataStream = mock(classOf[P2DataStream]),
                  mf: MessageFactory = mock(classOf[MessageFactory])) = TestFSMRef(new TradingEngine("000", 100) with ConnectionComponent
    with FutInfoDataStreamComponent with OptInfoDataStreamComponent with PosDataStreamComponent with MessageFactoryComponent {

    lazy val underlyingConnection = conn

    lazy val underlyingFutInfo = futInfo

    lazy val underlyingOptInfo = optInfo

    lazy val underlyingPos = pos

    implicit lazy val messageFactory = mf

  }, "Engine")
}