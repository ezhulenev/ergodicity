package com.ergodicity.core.session

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.plaza2.Repository.Snapshot
import com.ergodicity.plaza2.scheme.OptInfo.SessContentsRecord
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.plaza2.scheme.OptInfo
import akka.actor.ActorSystem
import com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging
import com.ergodicity.core.common.OptionContract

class StatelessSessionContentsSpec extends TestKit(ActorSystem("StatelessSessionContentsSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {

  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val rtsOption = SessContentsRecord(10881, 20023, 0, 3550, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

  "StatelessSessionContents" must {

    "should track session state updates and propagate to instrument state" in {
      val contents = TestActorRef(new StatelessSessionContents[OptionContract, OptInfo.SessContentsRecord](SessionState.Online), "Options")
      contents ! JoinSession(self)
      expectMsgType[SubscribeTransitionCallBack]

      contents ! Snapshot(self, rtsOption :: Nil)

      val instrument = system.actorFor("user/Options/" + contents.underlyingActor.conformIsinToActorName("RTS-6.12M150612PA 175000"))
      instrument ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(instrument, InstrumentState.Online))

      // Session suspended
      contents ! Transition(self, SessionState.Online, SessionState.Suspended)
      expectMsg(Transition(instrument, InstrumentState.Online, InstrumentState.Suspended))

      // Session Assigned
      contents ! Transition(self, SessionState.Suspended, SessionState.Assigned)
      expectMsg(Transition(instrument, InstrumentState.Suspended, InstrumentState.Assigned))

      // Session Canceled
      contents ! Transition(self, SessionState.Assigned, SessionState.Canceled)
      expectMsg(Transition(instrument, InstrumentState.Assigned, InstrumentState.Canceled))
    }
  }

}