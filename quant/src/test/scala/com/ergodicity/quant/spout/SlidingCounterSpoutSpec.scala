package com.ergodicity.quant.spout

import org.scalatest.{BeforeAndAfterAll, GivenWhenThen, WordSpec}
import org.joda.time.DateTime
import com.ergodicity.quant.{TestMarketEvent, AkkaConfigurations}
import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import org.scala_tools.time.Implicits._
import com.ergodicity.quant.computation.Counter
import com.ergodicity.quant.eip.Transformer

class SlidingCounterSpoutSpec extends TestKit(ActorSystem("SlidingCounterSpoutSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Sliding spout" must {

    "calculate results" in {
      val now = new DateTime
      val duration = 5.minutes

      val computation = TestActorRef(new SlidingSpout(Counter.sliding[TestMarketEvent](duration)))

      computation ! SubscribeComputation(self)
      computation ! Compute(TestMarketEvent(now))
      expectMsg(ComputationOutput(computation, 1))
    }

    "subscribe on slide out" in {
      val now = new DateTime
      val duration = 5.minutes

      val computation = TestActorRef(new SlidingSpout(Counter.sliding[TestMarketEvent](duration)))

      computation ! SubscribeComputation(self)
      computation ! SubscribeSlideOut(self)

      computation ! Compute(TestMarketEvent(now))
      expectMsg(ComputationOutput(computation, 1))

      computation ! Compute(TestMarketEvent(now + 1.second))
      expectMsg(ComputationOutput(computation, 2))

      computation ! Compute(TestMarketEvent(now + 5.minutes + 10.second))
      expectMsg(TestMarketEvent(now))
      expectMsg(TestMarketEvent(now+1.second))
      expectMsg(ComputationOutput(computation, 1))
    }

    "wire two sliding computations" in {
      val now = new DateTime
      val duration = 1.minute

      val computation1 = TestActorRef(new SlidingSpout(Counter.sliding[TestMarketEvent](duration)))
      val computation2 = TestActorRef(new SlidingSpout(Counter.sliding[TestMarketEvent](duration)))
      
      val transformer = TestActorRef(new Transformer(computation2, Compute(_)))

      computation1 ! SubscribeSlideOut(transformer)

      (0 to 70).foreach(i => {
        computation1 ! Compute(TestMarketEvent(now + i.seconds))
      })

      computation1 ! SubscribeComputation(self)
      computation2 ! SubscribeComputation(self)

      computation1 ! Compute(TestMarketEvent(now + 71.seconds))

      expectMsg(ComputationOutput(computation2, 12))
      expectMsg(ComputationOutput(computation1, 60))
    }
  }
}