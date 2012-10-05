package com.ergodicity.engine.strategy

import akka.actor.{Props, ActorSystem}
import akka.event.Logging
import akka.testkit._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import org.joda.time.{DurationFieldType, DateTime}
import com.ergodicity.core._
import akka.util.duration._
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
import com.ergodicity.engine.strategy.PriceRegression.PriceSlope
import trade.Trade
import com.ergodicity.core.FutureContract
import com.ergodicity.engine.{Services, StrategyEngine}
import org.mockito.Mockito
import com.ergodicity.engine.service.InstrumentData

class TrendFollowingSpec extends TestKit(ActorSystem("TrendFollowing", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
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

  "TrendFollowingStrategy" must {
    "catch instrument for given isin" in {
      implicit val Id1 = TrendFollowing.TrendFollowing(isin1)

      // Prepare mock for engine and services
      implicit val engine = mock(classOf[StrategyEngine])
      val services = mock(classOf[Services])
      Mockito.when(engine.services).thenReturn(services)
      Mockito.when(services.service(InstrumentData.InstrumentData)).thenReturn(instrumentDataService)

      // Build strategy
      val strategy = TestFSMRef(new TrendFollowingStrategy(isin1, 1.minute, 1.minute), "TrendFollowingStrategy")
      awaitCond(strategy.stateName == TrendFollowingState.Ready)

      verify(engine).reportReady(Map())
    }
  }

  "PriceRegressionActor" must {
    "return empty regression if insufficiend data available" in {
      val regression = TestActorRef(new PriceRegression(self)(1.minute, 1.minute), "PriceRegression")

      regression ! Trade(1, 1, IsinId(1), 100, 1, start, SystemTrade)
      val slope = receiveOne(100.millis).asInstanceOf[PriceSlope]
      assert(slope.primary.isNaN)
      assert(slope.secondary.isNaN)
    }

    "calculate regression matching in window" in {
      val regression = TestActorRef(new PriceRegression(self)(1.minute, 1.minute), "PriceRegression")

      regression ! Trade(1, 1, IsinId(1), 100, 1, start, SystemTrade)
      regression ! Trade(1, 1, IsinId(1), 110, 1, start.withFieldAdded(DurationFieldType.seconds(), 10), SystemTrade)
      regression ! Trade(1, 1, IsinId(1), 500, 1, start.withFieldAdded(DurationFieldType.seconds(), 20), SystemTrade)
      regression ! Trade(1, 1, IsinId(1), 500, 1, start.withFieldAdded(DurationFieldType.seconds(), 30), SystemTrade)
      regression ! Trade(1, 1, IsinId(1), 50, 1, start.withFieldAdded(DurationFieldType.seconds(), 40), SystemTrade)

      receiveWhile(1.second) {
        case r => log.info("regression = " + r)
      }
    }

    "discard outdated trades" in {
      val regression = TestFSMRef(new PriceRegression(self)(1.minute, 1.minute), "PriceRegression")

      regression ! Trade(1, 1, IsinId(1), 100, 1, start, SystemTrade)
      regression ! Trade(1, 1, IsinId(1), 150, 1, start.withFieldAdded(DurationFieldType.seconds(), 50), SystemTrade)
      assert(regression.stateData.primary.size == 2)
      log.info("Primary data = " + regression.stateData.primary)
      regression ! Trade(1, 1, IsinId(1), 120, 1, start.withFieldAdded(DurationFieldType.seconds(), 70), SystemTrade)
      assert(regression.stateData.primary.size == 2)
      log.info("Primary data = " + regression.stateData.primary)

      receiveWhile(1.second) {
        case r => log.info("regression = " + r)
      }
    }
  }
}