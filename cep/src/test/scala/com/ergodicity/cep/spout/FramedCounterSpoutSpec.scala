package com.ergodicity.cep.spout

import org.scalatest.{BeforeAndAfterAll, GivenWhenThen, WordSpec}
import akka.actor.ActorSystem
import akka.event.Logging
import org.joda.time.DateTime
import org.scala_tools.time.Implicits._
import com.ergodicity.cep.{TestMarketEvent, AkkaConfigurations}
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.cep.computation.FramedCounter

class FramedCounterSpoutSpec extends TestKit(ActorSystem("FramedCounterSpoutSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Framed spout" must {

    "calculate intermediate results" in {
      val now = new DateTime
      val initialInterval = now to now + 5.minutes

      val computation = TestActorRef(new FramedSpout(new FramedCounter[TestMarketEvent](initialInterval)))

      computation ! SubscribeIntermediateComputations(self)

      computation ! Compute(TestMarketEvent(now))
      expectMsg(IntermediateComputation(computation, 1))

      computation ! Compute(TestMarketEvent(now + 1.second))
      expectMsg(IntermediateComputation(computation, 2))
    }

    "calculate completed results" in {
      val now = new DateTime
      val initialInterval = now to now + 5.minutes

      val computation = TestActorRef(new FramedSpout(new FramedCounter[TestMarketEvent](initialInterval)))

      computation ! SubscribeComputation(self)

      computation ! Compute(TestMarketEvent(now + 1.second))
      computation ! Compute(TestMarketEvent(now + 2.second))
      computation ! Compute(TestMarketEvent(now + 3.second))
      computation ! Compute(TestMarketEvent(now + 4.second))
      computation ! Compute(TestMarketEvent(now + 5.second))

      computation ! Compute(TestMarketEvent(now + 6.minutes))

      expectMsg(ComputationOutput(computation, 5))
    }

    "calculate completed results with None" in {
      val now = new DateTime
      val initialInterval = now to now + 5.minutes

      val computation = TestActorRef(new FramedSpout(new FramedCounter[TestMarketEvent](initialInterval)))

      computation ! SubscribeComputation(self)

      computation ! Compute(TestMarketEvent(now + 6.minutes))

      expectMsg(ComputationOutput(computation, 0))
    }

    "calculate completed results for gaps" in {
      val now = new DateTime
      val initialInterval = now to now + 5.minutes

      val computation = TestActorRef(new FramedSpout(new FramedCounter[TestMarketEvent](initialInterval)))

      computation ! SubscribeComputation(self)
      computation ! SubscribeIntermediateComputations(self)

      computation ! Compute(TestMarketEvent(now + 11.minutes))

      expectMsg(ComputationOutput(computation, 0))
      expectMsg(ComputationOutput(computation, 0))
      expectMsg(IntermediateComputation(computation, 1))
    }

    "test multiple completed computations" in {
      val now = new DateTime
      val initialInterval = now to now + 5.minutes

      val computation = TestActorRef(new FramedSpout(new FramedCounter[TestMarketEvent](initialInterval)))

      computation ! SubscribeComputation(self)

      (0 to 3601).foreach(i => {
        computation ! Compute(TestMarketEvent(now + i.seconds))
      })

      (0 to 11).foreach(i => {
        expectMsg(ComputationOutput(computation, 300))
      })
    }
  }

}