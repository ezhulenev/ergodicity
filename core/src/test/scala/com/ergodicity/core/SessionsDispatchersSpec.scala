package com.ergodicity.core

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{TestProbe, TestActorRef, TestKit, ImplicitSender}
import akka.util.duration._
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.SysEvent.SessionDataReady
import com.ergodicity.cgate.scheme.{OptInfo, FutInfo}
import com.ergodicity.core.Mocking._
import com.ergodicity.core.SessionsTracking._
import com.ergodicity.core.session._
import org.scalatest.{WordSpec, GivenWhenThen, BeforeAndAfterAll}

class SessionsDispatchersSpec extends TestKit(ActorSystem("SessionsDispatchersSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll {
  val log = Logging(system, self)

  val OptionSessionId = 3547

  override def afterAll() {
    system.shutdown()
  }

  "FutInfo Dispatcher" must {
    "subscribe for stream events" in {
      val stream = TestProbe()
      val dispatcher = TestActorRef(new FutInfoDispatcher(self, stream.ref), "Dispatcher")
      stream.expectMsg(SubscribeStreamEvents(dispatcher))
    }

    "dispatch sessions" in {
      val dispatcher = TestActorRef(new FutInfoDispatcher(self, system.deadLetters), "Dispatcher")

      val session = mockSession(100, SessionState.Assigned)

      dispatcher ! StreamData(FutInfo.session.TABLE_INDEX, session.getData)

      val msg = receiveOne(100.millis).asInstanceOf[SessionEvent]
      assert(msg.id == SessionId(100, 100))
      assert(msg.state == SessionState.Assigned)
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
      val dispatcher = TestActorRef(new FutInfoDispatcher(self, system.deadLetters), "Dispatcher")

      val sysEvent = mockFutSysEvent(100, 1, 111)

      dispatcher ! StreamData(FutInfo.sys_events.TABLE_INDEX, sysEvent.getData)

      val msg = receiveOne(100.millis).asInstanceOf[FutSysEvent]
      assert(msg.event == SessionDataReady(100, 111))
    }
  }

  "OptInfo Dispatcher" must {
    "subscribe for stream events" in {
      val stream = TestProbe()
      val dispatcher = TestActorRef(new OptInfoDispatcher(self, stream.ref), "Dispatcher")
      stream.expectMsg(SubscribeStreamEvents(dispatcher))
    }

    "dispatch options" in {
      val dispatcher = TestActorRef(new OptInfoDispatcher(self, system.deadLetters), "Dispatcher")

      val option = mockOption(100, 1234, "Opt-Isin", "OPT", "Some Option contract", 115)
      dispatcher ! StreamData(OptInfo.opt_sess_contents.TABLE_INDEX, option.getData)

      val msg = receiveOne(100.millis).asInstanceOf[OptSessContents]
      assert(msg.sessionId == 100)
      assert(msg.instrument.security.id == IsinId(1234))

      expectNoMsg(300.millis)
    }

    "dispatch sys events" in {
      val dispatcher = TestActorRef(new OptInfoDispatcher(self, system.deadLetters), "Dispatcher")

      val sysEvent = mockOptSysEvent(100, 1, 111)

      dispatcher ! StreamData(OptInfo.sys_events.TABLE_INDEX, sysEvent.getData)

      val msg = receiveOne(100.millis).asInstanceOf[OptSysEvent]
      assert(msg.event == SessionDataReady(100, 111))
    }
  }


}
