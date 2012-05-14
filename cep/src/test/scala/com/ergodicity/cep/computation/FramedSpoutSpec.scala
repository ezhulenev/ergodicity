package com.ergodicity.cep.computation

import org.scalatest.{BeforeAndAfterAll, GivenWhenThen, WordSpec}
import akka.actor.ActorSystem
import akka.event.Logging
import org.joda.time.DateTime
import org.scala_tools.time.Implicits._
import com.ergodicity.cep.{TestPayload, AkkaConfigurations}
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}

class FramedSpoutSpec extends TestKit(ActorSystem("FramedSpoutSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Framed computation" must {

    "calculate intermediate results" in {
      val now = new DateTime
      val initialInterval = now to now + 5.minutes

      val computation = TestActorRef(new FramedSpout(new FramedCounter[TestPayload](initialInterval)))

      computation ! SubscribeIntermediateComputations(self)

      computation ! Compute(TestPayload(now))
      expectMsg(IntermediateComputation(computation, Some(1)))

      computation ! Compute(TestPayload(now + 1.second))
      expectMsg(IntermediateComputation(computation, Some(2)))
    }

    "calculate completed results with Some" in {
      val now = new DateTime
      val initialInterval = now to now + 5.minutes

      val computation = TestActorRef(new FramedSpout(new FramedCounter[TestPayload](initialInterval)))

      computation ! SubscribeComputation(self)

      computation ! Compute(TestPayload(now + 1.second))
      computation ! Compute(TestPayload(now + 2.second))
      computation ! Compute(TestPayload(now + 3.second))
      computation ! Compute(TestPayload(now + 4.second))
      computation ! Compute(TestPayload(now + 5.second))

      computation ! Compute(TestPayload(now + 6.minutes))

      expectMsg(ComputationOutput(computation, Some(5)))
    }

    "calculate completed results with None" in {
      val now = new DateTime
      val initialInterval = now to now + 5.minutes

      val computation = TestActorRef(new FramedSpout(new FramedCounter[TestPayload](initialInterval)))

      computation ! SubscribeComputation(self)

      computation ! Compute(TestPayload(now + 6.minutes))

      expectMsg(ComputationOutput(computation, None))
    }

    "calculate completed results for gaps" in {
      val now = new DateTime
      val initialInterval = now to now + 5.minutes

      val computation = TestActorRef(new FramedSpout(new FramedCounter[TestPayload](initialInterval)))

      computation ! SubscribeComputation(self)
      computation ! SubscribeIntermediateComputations(self)

      computation ! Compute(TestPayload(now + 11.minutes))

      expectMsg(ComputationOutput(computation, None))
      expectMsg(ComputationOutput(computation, None))
      expectMsg(IntermediateComputation(computation, Some(1)))
    }
    
    "test multiple completed computations" in {
      val now = new DateTime
      val initialInterval = now to now + 5.minutes

      val computation = TestActorRef(new FramedSpout(new FramedCounter[TestPayload](initialInterval)))

      computation ! SubscribeComputation(self)

      (0 to  3601).foreach(i => {
        computation ! Compute(TestPayload(now + i.seconds))
      })

      (0 to  11).foreach(i => {
        expectMsg(ComputationOutput(computation, Some(300)))
      })
    }
  }

}