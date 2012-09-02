package com.ergodicity.engine.strategy

import akka.event.Logging
import org.scalatest.{WordSpec, BeforeAndAfterAll, GivenWhenThen}
import akka.testkit._
import akka.actor._
import akka.util.duration._
import com.ergodicity.core.{SessionId, IsinId, ShortIsin, Isin}
import com.ergodicity.core.session.{IntradayClearingState, SessionState, SessionActor}
import com.ergodicity.core.Mocking._
import com.ergodicity.core.SessionsTracking.{OngoingSession, SubscribeOngoingSessions}
import com.ergodicity.core.session.Session
import com.ergodicity.core.session.SessionActor.FutInfoSessionContents
import com.ergodicity.cgate.repository.Repository.Snapshot
import com.ergodicity.engine.strategy.InstrumentWatchDog.Catched
import akka.actor.SupervisorStrategy.Stop
import com.ergodicity.core.session.Session
import com.ergodicity.cgate.repository.Repository.Snapshot
import com.ergodicity.core.SessionId
import scala.Some
import com.ergodicity.core.SessionsTracking.OngoingSession
import com.ergodicity.core.SessionsTracking.SubscribeOngoingSessions
import com.ergodicity.core.session.SessionActor.FutInfoSessionContents
import com.ergodicity.engine.strategy.InstrumentWatchDog.Catched
import akka.actor.AllForOneStrategy

class InstrumentWatchDogSpec extends TestKit(ActorSystem("InstrumentWatchDogSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val sessionId = 100
  val isinId = IsinId(166911)
  val isin = Isin("GMKR-6.12")
  val shortIsin = ShortIsin("GMM2")

  "InstrumentWatchDog" must {
    "subscribe for ongoing session" in {
      val instrumentData = TestProbe()
      val watchdog = TestFSMRef(new InstrumentWatchDog(isin, self, instrumentData.ref), "InstrumentWatchDog")

      instrumentData.expectMsg(SubscribeOngoingSessions(watchdog))
    }

    "catch instument" in {
      val instrumentData = TestProbe()
      val watchdog = TestFSMRef(new InstrumentWatchDog(isin, self, instrumentData.ref), "InstrumentWatchDog")

      watchdog ! OngoingSession(Some((SessionId(sessionId, sessionId), buildSessionActor)))

      val catched = receiveOne(200.millis)
      assert(catched match {
        case catched: Catched =>
          log.info("Catched = " + catched)
          true
        case _ => false
      })

    }

    "fail watching instument that is not assigned" in {
      val instrumentData = TestProbe()

      val guard = TestActorRef(new Actor {
        val watchdog = context.actorOf(Props(new InstrumentWatchDog(Isin("BadIsin"), self, instrumentData.ref)), "InstrumentWatchDog")
        watchdog ! OngoingSession(Some((SessionId(sessionId, sessionId), buildSessionActor)))


        override def supervisorStrategy() = AllForOneStrategy() {
          case _: InstrumentWatcherException => Stop
        }

        protected def receive = null
      })

      watch(guard.underlyingActor.watchdog)
      expectMsg(Terminated(guard.underlyingActor.watchdog))
    }

  }

  private def buildSessionActor = {
    val session = Session(sessionId, sessionId, null, None, None, null)
    val sessionActor = TestActorRef(new SessionActor(session, SessionState.Online, IntradayClearingState.Oncoming), "Session2")
    val future = mockFuture(4023, isinId.id, isin.isin, shortIsin.shortIsin, "Фьючерсный контракт GMKR-06.12", 115, 2)
    sessionActor ! FutInfoSessionContents(Snapshot(self, future :: Nil))
    sessionActor
  }


}
