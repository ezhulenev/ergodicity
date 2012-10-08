package com.ergodicity.engine.strategy

import akka.actor.{Actor, Props, ActorSystem}
import akka.event.Logging
import akka.testkit._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import org.joda.time.DateTime
import com.ergodicity.core._
import position.Position
import session.InstrumentParameters.FutureParameters
import session.InstrumentParameters.Limits
import session.{IntradayClearingState, SessionState, Session, InstrumentState}
import session.SessionActor.AssignedContents
import com.ergodicity.core.SessionsTracking._
import org.mockito.Mockito._
import com.ergodicity.core.SessionsTracking.FutSysEvent
import com.ergodicity.cgate.SysEvent.SessionDataReady
import com.ergodicity.core.SessionsTracking.FutSessContents
import com.ergodicity.core.SessionId
import com.ergodicity.core.SessionsTracking.SessionEvent
import com.ergodicity.core.FutureContract
import com.ergodicity.engine.{Services, StrategyEngine}
import org.mockito.Mockito
import com.ergodicity.engine.service.InstrumentData
import com.ergodicity.engine.strategy.PositionManagement.{PositionBalanced, PositionManagerStarted}

class PositionManagerSpec extends TestKit(ActorSystem("PositionManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val sessionId = SessionId(100, 100)

  implicit val isin1 = Isin("RTS-9.12")
  implicit val isinId1 = IsinId(100)

  implicit val isin2 = Isin("RTS-12.12")
  implicit val isinId2 = IsinId(101)

  val futureContract1 = FutureContract(isinId1, isin1, ShortIsin(""), "Future Contract #1")
  val futureContract2 = FutureContract(isinId2, isin2, ShortIsin(""), "Future Contract #2")

  val parameters1 = FutureParameters(100, Limits(10, 20))
  val parameters2 = FutureParameters(1000, Limits(100, 200))

  val assignedContents = AssignedContents(Set(futureContract1, futureContract2))

  private def instrumentDataService = {
    val sessionsTracking = system.actorOf(Props(new SessionsTracking(system.deadLetters, system.deadLetters)), "Sessions")
    sessionsTracking ! FutSessContents(sessionId.fut, futureContract1, parameters1, InstrumentState.Online)
    sessionsTracking ! FutSessContents(sessionId.fut, futureContract2, parameters2, InstrumentState.Online)
    sessionsTracking ! SessionEvent(sessionId, mock(classOf[Session]), SessionState.Online, IntradayClearingState.Oncoming)
    sessionsTracking ! FutSysEvent(SessionDataReady(1, 100))
    sessionsTracking ! OptSysEvent(SessionDataReady(1, 100))
    sessionsTracking
  }

  val SystemTrade = false
  val start = new DateTime(2012, 1, 1, 12, 0)

  class DummyStrategy(val engine: StrategyEngine) extends Actor with Strategy with InstrumentWatcher {
    protected def receive = null
  }

  "PositionManager" must {
    "wait for position manager catch instrument and goes to balanced state" in {

      // Prepare mock for engine and services
      implicit val engine = mock(classOf[StrategyEngine])
      val services = mock(classOf[Services])
      Mockito.when(engine.services).thenReturn(services)
      Mockito.when(services.service(InstrumentData.InstrumentData)).thenReturn(instrumentDataService)

      // Build manager
      implicit val config = PositionManagementConfig(self)
      implicit val watcher = TestActorRef(new DummyStrategy(engine)).underlyingActor.asInstanceOf[InstrumentWatcher]
      val positionManager = TestFSMRef(new PositionManagerActor(system.deadLetters, isin1, Position.flat), "PositionManager")

      awaitCond(positionManager.stateName == PositionManagerState.Balanced)

      expectMsg(PositionManagerStarted(isin1, futureContract1, Position.flat))
      expectMsg(PositionBalanced(isin1, Position.flat))
    }
  }
}