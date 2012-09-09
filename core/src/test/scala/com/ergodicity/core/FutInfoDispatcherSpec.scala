package com.ergodicity.core

import akka.event.Logging
import org.scalatest.{WordSpec, GivenWhenThen, BeforeAndAfterAll}
import akka.testkit.{TestProbe, TestActorRef, TestKit, ImplicitSender}
import akka.actor.{ActorRef, ActorSystem}
import session.SessionActor.GetInstrumentActor
import session.{Instrument, InstrumentState, SessionActor, Session}
import com.ergodicity.core.Mocking._
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack}
import akka.util.duration._
import akka.dispatch.Await
import akka.util.Duration
import java.util.concurrent.TimeUnit
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.StreamEvent
import com.ergodicity.cgate.scheme.FutInfo
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.core.SessionsTracking.FutSessContents

class FutInfoDispatcherSpec extends TestKit(ActorSystem("FutInfoDispatcherSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll {
  val log = Logging(system, self)

  val OptionSessionId = 3547

  override def afterAll() {
    system.shutdown()
  }

  "FutInfo Dispatcher" must {
    "subscribe for stream events" in {
      val stream = TestProbe()
      TestActorRef(new FutInfoDispatcher(self, stream.ref), "Dispatcher")
      stream.expectMsg(SubscribeStreamEvents(self))
    }

    "dispatch sessions" in {

    }

    "dispatch only futures and skip repo" in {
      val dispatcher = TestActorRef(new FutInfoDispatcher(self, system.deadLetters), "Dispatcher")

      val future = mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, 2)
      val repo = mockFuture(4023, 170971, "HYDR-16.04.12R3", "HYDRT0T3", "Репо инструмент на ОАО \"ГидроОГК\"", 4965, 2, 1)

      dispatcher ! StreamData(FutInfo.fut_sess_contents.TABLE_INDEX, future.getData)
      dispatcher ! StreamData(FutInfo.fut_sess_contents.TABLE_INDEX, repo.getData)

      val msg = receiveOne(100.millis).asInstanceOf[FutSessContents]
      assert(msg.sessionId == 4023)
      assert(msg.instrument.security.id == IsinId(166911))
      assert(msg.state == InstrumentState.Suspended)

      expectNoMsg(300.millis)
    }

    "dispatch sys events" in {

    }
  }

}
