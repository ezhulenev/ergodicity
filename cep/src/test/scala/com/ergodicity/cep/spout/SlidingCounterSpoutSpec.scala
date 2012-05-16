package com.ergodicity.cep.spout

import org.scalatest.{BeforeAndAfterAll, GivenWhenThen, WordSpec}
import org.joda.time.DateTime
import com.ergodicity.cep.{TestMarketEvent, AkkaConfigurations}
import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import org.scala_tools.time.Implicits._
import com.ergodicity.cep.computation.Counter

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
  }
}