package com.ergodicity.cgate

import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import ru.micexrts.cgate.{Listener => CGListener}
import akka.actor.{Terminated, FSM, ActorSystem}
import Listener._
import ru.micexrts.cgate
import akka.dispatch.Future


class ListenerSpec extends TestKit(ActorSystem("ListenerSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  val Host = "host"
  val Port = 4001
  val AppName = "ConnectionSpec"

  override def afterAll() {
    system.shutdown()
  }

  def withListener(listener: CGListener) = new WithListener {
    implicit val ec = system.dispatcher
    def apply[T](f: (cgate.Listener) => T)(implicit m: Manifest[T]) = Future {f(listener)}
  }

  "Listener" must {
    "be initialized in Closed state" in {
      val cg = mock(classOf[CGListener])

      val listener = TestFSMRef(new Listener(withListener(cg), None), "Listener")
      log.info("State: " + listener.stateName)
      assert(listener.stateName == Closed)
    }

    "terminate after Listener gone to Error state" in {
      val cg = mock(classOf[CGListener])

      val listener = TestFSMRef(new Listener(withListener(cg), None), "Listener")
      watch(listener)
      listener ! ListenerState(Error)
      expectMsg(Terminated(listener))
    }

    "return to Closed state after Close listener sent" in {
      val cg = mock(classOf[CGListener])

      val listener = TestFSMRef(new Listener(withListener(cg), None), "Listener")
      watch(listener)
      listener ! Close
      assert(listener.stateName == Closed)
    }

    "terminate on FSM.StateTimeout in Opening state" in {
      val cg = mock(classOf[CGListener])

      val listener = TestFSMRef(new Listener(withListener(cg), None), "Listener")
      listener.setState(Opening)
      watch(listener)
      listener ! FSM.StateTimeout
      expectMsg(Terminated(listener))
    }
  }

}
