package com.ergodicity.core.session

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.actor.ActorSystem
import com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging
import com.ergodicity.core.common.OptionContract
import com.ergodicity.core.Mocking._
import com.ergodicity.cgate.scheme.OptInfo
import com.ergodicity.cgate.repository.Repository.Snapshot
import akka.testkit.{TestProbe, TestActorRef, ImplicitSender, TestKit}

class StatelessSessionContentsSpec extends TestKit(ActorSystem("StatelessSessionContentsSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {

  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val rtsOption = mockOption(3550, 160734, "RTS-6.12M150612PA 175000", "RI175000BR2", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

  "StatelessSessionContents" must {

    "should track session state updates and propagate to instrument state" in {
      val session = TestProbe()
      val contents = TestActorRef(new StatelessSessionContents[OptionContract, OptInfo.opt_sess_contents], "Options")

      session.expectMsgType[SubscribeTransitionCallBack]
      contents ! CurrentState(session.ref, SessionState.Online)

      contents ! Snapshot(self, rtsOption :: Nil)

      val instrument = system.actorFor("user/Options/" + contents.underlyingActor.conformIsinToActorName("RTS-6.12M150612PA 175000"))
      instrument ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(instrument, InstrumentState.Online))

      // Session suspended
      contents ! Transition(session.ref, SessionState.Online, SessionState.Suspended)
      expectMsg(Transition(instrument, InstrumentState.Online, InstrumentState.Suspended))

      // Session Assigned
      contents ! Transition(session.ref, SessionState.Suspended, SessionState.Assigned)
      expectMsg(Transition(instrument, InstrumentState.Suspended, InstrumentState.Assigned))

      // Session Canceled
      contents ! Transition(session.ref, SessionState.Assigned, SessionState.Canceled)
      expectMsg(Transition(instrument, InstrumentState.Assigned, InstrumentState.Canceled))
    }
  }

}