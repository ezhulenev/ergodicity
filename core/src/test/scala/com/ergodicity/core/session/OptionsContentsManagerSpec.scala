package com.ergodicity.core.session

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.{ActorRef, ActorSystem}
import com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging
import com.ergodicity.core.Mocking._
import com.ergodicity.cgate.scheme.OptInfo
import akka.testkit._
import akka.actor.FSM.Transition
import com.ergodicity.cgate.repository.Repository.Snapshot
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.testkit.TestActor.AutoPilot
import com.ergodicity.core.session.Session.GetState
import Implicits._

class OptionsContentsManagerSpec extends TestKit(ActorSystem("OptionsContentsManagerSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {

  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val rtsOption = mockOption(3550, 160734, "RTS-6.12M150612PA 175000", "RI175000BR2", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

  "StatelessSessionContents" must {

    "should track session state updates and propagate to instrument state" in {
      val session: ActorRef = onlineSession
      val contents = TestActorRef(new SessionContents[OptInfo.opt_sess_contents](session) with OptionsContentsManager, "Options")
      contents ! Snapshot(self, rtsOption :: Nil)

      val instrument = system.actorFor("user/Options/" + contents.underlyingActor.conformIsinToActorName("RTS-6.12M150612PA 175000"))
      instrument ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(instrument, InstrumentState.Online))

      // Session suspended
      contents ! Transition(session, SessionState.Online, SessionState.Suspended)
      expectMsg(Transition(instrument, InstrumentState.Online, InstrumentState.Suspended))

      // Session Assigned
      contents ! Transition(session, SessionState.Suspended, SessionState.Assigned)
      expectMsg(Transition(instrument, InstrumentState.Suspended, InstrumentState.Assigned))

      // Session Canceled
      contents ! Transition(session, SessionState.Assigned, SessionState.Canceled)
      expectMsg(Transition(instrument, InstrumentState.Assigned, InstrumentState.Canceled))
    }
  }

  def onlineSession = {
    val session = TestProbe()
    session.setAutoPilot(new AutoPilot {
      def run(sender: ActorRef, msg: Any) = msg match {
        case GetState =>
          sender ! SessionState.Online
          None
      }
    })
    session.ref
  }

}