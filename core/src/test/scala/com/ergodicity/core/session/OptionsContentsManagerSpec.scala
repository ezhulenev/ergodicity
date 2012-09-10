package com.ergodicity.core.session

import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.testkit.TestActor.AutoPilot
import akka.testkit._
import com.ergodicity.core.AkkaConfigurations.ConfigWithDetailedLogging
import com.ergodicity.core.SessionsTracking.OptSessContents
import com.ergodicity.core._
import com.ergodicity.core.session.Instrument.Limits
import com.ergodicity.core.session.SessionActor.GetState
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}

class OptionsContentsManagerSpec extends TestKit(ActorSystem("OptionsContentsManagerSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {

  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val id = IsinId(160734)
  val isin = Isin("RTS-6.12M150612PA 175000")
  val shortIsin = ShortIsin("RI175000BR2")

  val instrument = Instrument(OptionContract(id, isin, shortIsin, "Option Contract"), Limits(100, 100))

  "OptionsContentsManager" must {

    "track session state updates and propagate to instrument state" in {
      val session: ActorRef = onlineSession
      val contents = TestActorRef(new SessionContents[OptSessContents](session) with OptionsContentsManager, "Options")

      when("receive new instrument")
      contents ! OptSessContents(100, instrument)

      then("should create actor for it")
      val instrumentActor = system.actorFor("user/Options/" + Isin("RTS-6.12M150612PA 175000").toActorName)
      instrumentActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(instrumentActor, InstrumentState.Online))

      when("session suspended")
      contents ! Transition(session, SessionState.Online, SessionState.Suspended)
      then("instrument should be suspended too")
      expectMsg(Transition(instrumentActor, InstrumentState.Online, InstrumentState.Suspended))

      when("session assigned")
      contents ! Transition(session, SessionState.Suspended, SessionState.Assigned)
      then("instrument should be assigned too")
      expectMsg(Transition(instrumentActor, InstrumentState.Suspended, InstrumentState.Assigned))

      when("session cancelled")
      contents ! Transition(session, SessionState.Assigned, SessionState.Canceled)
      then("instrument should be cancelled too")
      expectMsg(Transition(instrumentActor, InstrumentState.Assigned, InstrumentState.Canceled))
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