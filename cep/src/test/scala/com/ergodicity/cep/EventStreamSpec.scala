package com.ergodicity.cep

import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, GivenWhenThen, WordSpec}
import akka.event.Logging
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}

class EventStreamSpec extends TestKit(ActorSystem("EventStreamSpec")) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "EventStream" must {
    "twice input and send to subscriber" in {
      val stream = TestActorRef(new EventStream[Int] with Multiplier with Twice, "TwiceStream")
      stream ! CollectComputations(self)
      stream ! 2
      expectMsg(4)
    }
  }
}

trait Multiplier {
  this: EventStream[Int] =>

  def k: Int

  onEvent {
    case i => i * k
  }
}

trait Twice extends Multiplier {
  this: EventStream[Int] =>
  val k = 2
}