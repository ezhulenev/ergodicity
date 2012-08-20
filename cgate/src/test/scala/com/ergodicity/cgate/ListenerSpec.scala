package com.ergodicity.cgate

import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import ru.micexrts.cgate.{Listener => CGListener}
import akka.actor.{FSM, ActorSystem}
import Listener._


class ListenerSpec extends TestKit(ActorSystem("ListenerSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Listener" must {
    "be initialized in Closed state" in {
      val cg = mock(classOf[CGListener])

      val listener = TestFSMRef(new Listener(cg, None), "Listener")
      log.info("State: " + listener.stateName)
      assert(listener.stateName == Closed)
    }

    "fail on Listener gone to Error state" in {
      val cg = mock(classOf[CGListener])

      val listener = TestFSMRef(new Listener(cg, None), "Listener")

      intercept[ListenerError] {
        listener receive ListenerState(Error)
      }
    }

    "return to Closed state after Close listener sent" in {
      val cg = mock(classOf[CGListener])

      val listener = TestFSMRef(new Listener(cg, None), "Listener")
      watch(listener)
      listener ! Close
      assert(listener.stateName == Closed)
    }

    "terminate on FSM.StateTimeout in Opening state" in {
      val cg = mock(classOf[CGListener])

      val listener = TestFSMRef(new Listener(cg, None), "Listener")
      listener.setState(Opening)
      watch(listener)
      intercept[Listener.OpenTimedOut] {
        listener receive FSM.StateTimeout
      }
    }
  }

}
