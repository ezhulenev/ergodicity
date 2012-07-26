package com.ergodicity.engine

import component._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import org.mockito.Mockito._
import TradingEngineState._
import akka.actor.{Terminated, ActorSystem}
import akka.actor.FSM.Transition
import com.ergodicity.cgate.{Opening, Active}
import ru.micexrts.cgate.{ISubscriber, Connection => CGConnection, Listener => CGListener}

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
      val p2 = mock(classOf[CGConnection])
      val engine = buildEngine(p2)
      engine ! StartTradingEngine

      // Let message be propagated to Connection actor
      Thread.sleep(100)

      verify(p2).open("")

      assert(engine.stateName == TradingEngineState.Connecting)
    }

    "stop on connection terminated" in {
      val engine = buildEngine()
      val underlying = engine.underlyingActor.asInstanceOf[TradingEngine]

      engine.setState(Connecting)
      watch(engine)
      
      Thread.sleep(100)

      engine ! Terminated(underlying.Connection)
      expectMsg(Terminated(engine))
    }

    "goto Initializing state after connection connected" in {
      val engine = buildEngine()
      val underlying = engine.underlyingActor.asInstanceOf[TradingEngine]

      engine.setState(Connecting)

      engine ! Transition(underlying.Connection, Opening, Active)
      assert(engine.stateName == TradingEngineState.Initializing)
    }
  }

  def buildEngine(conn: CGConnection = mock(classOf[CGConnection]),
                  futInfo: CGListener = mock(classOf[CGListener]),
                  optInfo: CGListener = mock(classOf[CGListener]),
                  pos: CGListener = mock(classOf[CGListener])) = TestFSMRef(new TradingEngine(100) with ConnectionComponent
    with FutInfoListenerComponent with OptInfoListenerComponent with PosListenerComponent {

    lazy val underlyingConnection = conn

    lazy val underlyingFutInfoListener = (_:ISubscriber) => futInfo

    lazy val underlyingOptInfoListener = (_:ISubscriber) => optInfo

    lazy val underlyingPosListener = (_:ISubscriber) => pos
    
    }, "Engine")
}