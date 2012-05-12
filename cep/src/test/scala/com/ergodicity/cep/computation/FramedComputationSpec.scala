package com.ergodicity.cep.computation

import org.scalatest.{BeforeAndAfterAll, GivenWhenThen, WordSpec}
import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import org.joda.time.DateTime
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import org.scala_tools.time.Implicits._
import com.ergodicity.cep.{TestPayload, AkkaConfigurations}

class FramedComputationSpec extends TestKit(ActorSystem("FramedComputationSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Framed computation" must {
    "be initialized in Idle state" in {
      val computation = TestFSMRef(new FramedComputation)
      assert(computation.stateName == Idle)
    }

    "work with counter" in {
      val now = new DateTime
      val initialInterval = now to now + 5.minutes

      val computation = TestFSMRef(new FramedComputation)

      computation ! SubscribeTransitionCallBack(self)
      receiveN(1)

      computation ! SubscribeComputation(self)

      computation ! SetUpFramedComputation(initialInterval, Counter[TestPayload](0))
      expectMsg(Transition(computation, Idle, Online))

      computation ! TestPayload(now)
      expectMsg(TemporalComputation(computation, initialInterval, 1))

      computation ! TestPayload(now + 1.second)
      expectMsg(TemporalComputation(computation, initialInterval, 2))
    }
  }

}