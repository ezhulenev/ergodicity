package com.ergodicity.engine.strategy

import akka.event.Logging
import org.scalatest.{WordSpec, BeforeAndAfterAll, GivenWhenThen}
import akka.testkit._
import akka.actor._
import akka.util.duration._
import com.ergodicity.core.{IsinId, ShortIsin, Isin}
import com.ergodicity.core.session._
import com.ergodicity.core.Mocking._
import com.ergodicity.engine.strategy.InstrumentWatchDog._
import akka.actor.SupervisorStrategy.Stop
import akka.actor.FSM.{Transition, CurrentState}
import com.ergodicity.core.session.Session
import com.ergodicity.cgate.repository.Repository.Snapshot
import com.ergodicity.core.SessionId
import scala.Some
import com.ergodicity.engine.strategy.InstrumentWatchDog.WatchDogConfig
import com.ergodicity.core.SessionsTracking.OngoingSession
import com.ergodicity.core.SessionsTracking.SubscribeOngoingSessions
import com.ergodicity.core.session.SessionActor.FutInfoSessionContents
import com.ergodicity.engine.strategy.InstrumentWatchDog.Catched
import com.ergodicity.core.SessionsTracking.OngoingSessionTransition
import akka.actor.Terminated
import akka.actor.AllForOneStrategy

class InstrumentWatchDogSpec extends TestKit(ActorSystem("InstrumentWatchDogSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val config = WatchDogConfig(self)

  val sessionId = SessionId(100, 100)
  val isinId = IsinId(166911)
  val isin = Isin("GMKR-6.12")
  val shortIsin = ShortIsin("GMM2")

  "InstrumentWatchDog" must {
    "subscribe for ongoing session" in {
      val instrumentData = TestProbe()
      val watchdog = TestFSMRef(new InstrumentWatchDog(isin, instrumentData.ref), "InstrumentWatchDog")

      instrumentData.expectMsg(SubscribeOngoingSessions(watchdog))
    }

    "catch instument" in {
      val instrumentData = TestProbe()
      val watchdog = TestFSMRef(new InstrumentWatchDog(isin, instrumentData.ref), "InstrumentWatchDog")

      watchdog ! OngoingSession(Some((sessionId, buildSessionActor(sessionId))))

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
        val watchdog = context.actorOf(Props(new InstrumentWatchDog(Isin("BadIsin"), instrumentData.ref)(config.copy(reportTo = system.deadLetters))), "InstrumentWatchDog")
        watchdog ! OngoingSession(Some((sessionId, buildSessionActor(sessionId))))


        override def supervisorStrategy() = AllForOneStrategy() {
          case _: InstrumentWatcherException => Stop
        }

        protected def receive = null
      })

      watch(guard.underlyingActor.watchdog)
      expectMsg(Terminated(guard.underlyingActor.watchdog))
    }

    "catch new instrument on session reassigned" in {
      val instrumentData = TestProbe()
      val watchdog = TestFSMRef(new InstrumentWatchDog(isin, instrumentData.ref), "InstrumentWatchDog")

      val oldSession = Some((sessionId, buildSessionActor(sessionId)))
      watchdog ! OngoingSession(oldSession)
      expectMsgType[Catched]

      val newId = SessionId(101, 101)
      val newSession = Some((newId, buildSessionActor(newId)))
      watchdog ! OngoingSessionTransition(oldSession, newSession)
      expectMsgType[Catched]
    }

    "fail on lost ongoing session" in {
      val instrumentData = TestProbe()

      val guard = TestActorRef(new Actor {
        val watchdog = context.actorOf(Props(new InstrumentWatchDog(isin, instrumentData.ref)(config.copy(reportTo = system.deadLetters))), "InstrumentWatchDog")
        watchdog ! OngoingSession(Some((sessionId, buildSessionActor(sessionId))))
        watchdog ! OngoingSessionTransition(Some((sessionId, buildSessionActor(sessionId))), None)

        override def supervisorStrategy() = AllForOneStrategy() {
          case _: InstrumentWatcherException => Stop
        }

        protected def receive = null
      })

      watch(guard.underlyingActor.watchdog)
      expectMsg(Terminated(guard.underlyingActor.watchdog))
    }

    "fail on catched instrument terminated" in {
      val instrumentData = TestProbe()

      val guard = TestActorRef(new Actor {
        val watchdog = context.actorOf(Props(new InstrumentWatchDog(isin, instrumentData.ref)), "InstrumentWatchDog")
        watchdog ! OngoingSession(Some((sessionId, buildSessionActor(sessionId))))

        val catched = receiveOne(100.millis).asInstanceOf[Catched]
        watchdog ! Terminated(catched.ref)

        override def supervisorStrategy() = AllForOneStrategy() {
          case _: InstrumentWatcherException => Stop
        }

        protected def receive = null
      })

      watch(guard.underlyingActor.watchdog)
      expectMsg(Terminated(guard.underlyingActor.watchdog))
    }

    "notify on catched instrument states" in {
      val instrumentData = TestProbe()
      val watchdog = TestFSMRef(new InstrumentWatchDog(isin, instrumentData.ref)(config.copy(notifyOnState = true)), "InstrumentWatchDog")

      watchdog ! OngoingSession(Some((sessionId, buildSessionActor(sessionId))))

      val catched = receiveOne(100.millis).asInstanceOf[Catched]
      expectMsg(CatchedState(isin, InstrumentState.Suspended))

      watchdog ! CurrentState(catched.ref, InstrumentState.Assigned)
      expectMsg(CatchedState(isin, InstrumentState.Assigned))

      watchdog ! Transition(catched.ref, InstrumentState.Assigned, InstrumentState.Online)
      expectMsg(CatchedState(isin, InstrumentState.Online))
    }

  }

  private def buildSessionActor(id: SessionId) = {
    val session = Session(id.id, id.optionSessionId, null, None, None, null)
    val sessionActor = TestActorRef(new SessionActor(session, SessionState.Online, IntradayClearingState.Oncoming), "Session2")
    val future = mockFuture(id.id, isinId.id, isin.isin, shortIsin.shortIsin, "Фьючерсный контракт GMKR-06.12", 115, 2)
    sessionActor ! FutInfoSessionContents(Snapshot(self, future :: Nil))
    Thread.sleep(100)
    sessionActor
  }


}
