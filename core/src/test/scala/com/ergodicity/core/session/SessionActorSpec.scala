package com.ergodicity.core.session

import SessionState._
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.Await
import akka.event.Logging
import akka.pattern.ask
import akka.testkit.{TestActorRef, ImplicitSender, TestFSMRef, TestKit}
import akka.util.{Duration, Timeout}
import com.ergodicity.core.AkkaConfigurations._
import com.ergodicity.core.SessionsTracking.{OptSessContents, FutSessContents}
import com.ergodicity.core._
import session.InstrumentParameters.{OptionParameters, FutureParameters, Limits}
import com.ergodicity.core.session.SessionActor._
import java.util.concurrent.TimeUnit
import org.joda.time.DateTime
import org.scala_tools.time.Implicits._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}

class SessionActorSpec extends TestKit(ActorSystem("SessionActorSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  implicit val timeout = Timeout(1, TimeUnit.SECONDS)
  val primaryInterval = new DateTime to new DateTime
  val positionTransferInterval = new DateTime to new DateTime

  override def afterAll() {
    system.shutdown()
  }

  "SessionState" must {
    "support all codes" in {
      assert(SessionState(0) == Assigned)
      assert(SessionState(1) == Online)
      assert(SessionState(2) == Suspended)
      assert(SessionState(3) == Canceled)
      assert(SessionState(4) == Completed)
    }
  }

  "IntradayClearing" must {
    "handle state updates" in {
      val clearing = TestFSMRef(new IntradayClearing, "IntradayClearing")
      assert(clearing.stateName == IntradayClearingState.Undefined)

      clearing ! IntradayClearingState.Completed
      log.info("State: " + clearing.stateName)
      assert(clearing.stateName == IntradayClearingState.Completed)
    }
  }

  "Session" must {
    "be inititliazed in given state and terminate child clearing actor" in {
      val content = Session(SessionId(100, 101), primaryInterval, None, None, positionTransferInterval)
      val session = TestFSMRef(SessionActor(content), "Session")

      val intClearing = session.underlyingActor.asInstanceOf[SessionActor].intradayClearing
      intClearing ! SubscribeTransitionCallBack(self)

      expectMsg(CurrentState(intClearing, IntradayClearingState.Undefined))
    }

    "apply state and int. clearing updates" in {
      val content = Session(SessionId(100, 101), primaryInterval, None, None, positionTransferInterval)
      val session = TestFSMRef(SessionActor(content), "Session")

      // Subscribe for clearing transitions
      val intClearing = session.underlyingActor.asInstanceOf[SessionActor].intradayClearing
      intClearing ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(intClearing, IntradayClearingState.Undefined))

      session ! SessionState.Suspended
      assert(session.stateName == SessionState.Suspended)

      session ! IntradayClearingState.Running
      expectMsg(Transition(intClearing, IntradayClearingState.Undefined, IntradayClearingState.Running))
    }

    "forward FutSessContents to contents manager" in {
      val id = IsinId(166911)
      val isin = Isin("GMKR-6.12")
      val shortIsin = ShortIsin("GMM2")

      val contract = FutureContract(id, isin, shortIsin, "Future Contract")

      val content = Session(SessionId(100, 101), primaryInterval, None, None, positionTransferInterval)
      val session = TestActorRef(new SessionActor(content), "Session2")

      when("session received new instrument")
      session ! FutSessContents(100, contract, FutureParameters(100, Limits(100, 100)), InstrumentState.Suspended)
      Thread.sleep(300)

      then("actor for it should be created in contents manager")
      val gmkFutures = system.actorFor("user/Session2/Futures/GMKR-6.12")
      gmkFutures ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(gmkFutures, InstrumentState.Suspended))

      val instrumentActor = (session ? GetInstrumentActor(Isin("GMKR-6.12"))).mapTo[ActorRef]
      assert(Await.result(instrumentActor, Duration(1, TimeUnit.SECONDS)) == gmkFutures)
    }

    "return all assigned contents" in {
      val future = FutureContract(IsinId(166911), Isin("GMKR-6.12"), ShortIsin("GMM2"), "Future Contract")
      val option = OptionContract(IsinId(160734), Isin("RTS-6.12M150612PA 175000"), ShortIsin("RI175000BR2"), "Option Contract")

      val session = Session(SessionId(100, 101), primaryInterval, None, None, positionTransferInterval)
      val sessionActor = TestActorRef(new SessionActor(session), "Session2")

      given("session with assigned contents")
      sessionActor ! FutSessContents(100, future, FutureParameters(100, Limits(100, 100)), InstrumentState.Assigned)
      sessionActor ! OptSessContents(100, option, OptionParameters(100))
      Thread.sleep(100)

      when("asked for contents assigned")
      val assignedFuture = (sessionActor ? GetAssignedContents).mapTo[AssignedContents]
      val assigned = Await.result(assignedFuture, Duration(1, TimeUnit.SECONDS))

      then("should return all assigned insturment")
      log.info("Assigned contents = " + assigned)
      assert(assigned.contents.size == 2)
    }

    "fail if no instument found" in {
      val future = FutureContract(IsinId(166911), Isin("GMKR-6.12"), ShortIsin("GMM2"), "Future Contract")
      val option = OptionContract(IsinId(160734), Isin("RTS-6.12M150612PA 175000"), ShortIsin("RI175000BR2"), "Option Contract")

      val session = Session(SessionId(100, 101), primaryInterval, None, None, positionTransferInterval)
      val sessionActor = TestActorRef(new SessionActor(session), "Session2")

      sessionActor ! FutSessContents(100, future, FutureParameters(100, Limits(100, 100)), InstrumentState.Assigned)
      sessionActor ! OptSessContents(100, option, OptionParameters(100))

      val result = (sessionActor ? GetInstrumentActor(Isin("BadCode"))).mapTo[ActorRef]
      intercept[InstrumentIsinNotAssigned] {
        Await.result(result, Duration(1, TimeUnit.SECONDS))
      }
    }
  }
}