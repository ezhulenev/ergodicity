package com.ergodicity.core.broker

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations
import akka.actor.{FSM, Terminated, ActorSystem}
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import akka.util.Timeout
import akka.util.duration._
import akka.dispatch.Future
import ru.micexrts.cgate.{Publisher => CGPublisher}
import org.mockito.Mockito._
import com.ergodicity.cgate.{Closed, Opening}

class BrokerSpec extends TestKit(ActorSystem("BrokerSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)


  implicit val timeout = Timeout(5 seconds)

  override def afterAll() {
    system.shutdown()
  }

  def withPublisher(publisher: CGPublisher) = new WithPublisher {
    implicit val ec = system.dispatcher

    def apply[T](f: (CGPublisher) => T)(implicit m: Manifest[T]) = Future {
      f(publisher)
    }
  }

  implicit val config = Broker.Config("000")

  "Broker" must {
    "be initialized in Closed state" in {
      val cg = mock(classOf[CGPublisher])

      val listener = TestFSMRef(new Broker(withPublisher(cg), None), "Broker")
      log.info("State: " + listener.stateName)
      assert(listener.stateName == Closed)
    }

    "terminate after Publisher gone to Error state" in {
      val cg = mock(classOf[CGPublisher])

      val listener = TestFSMRef(new Broker(withPublisher(cg), None), "Broker")
      watch(listener)
      listener ! PublisherState(com.ergodicity.cgate.Error)
      expectMsg(Terminated(listener))
    }

    "return to Closed state after Close listener sent" in {
      val cg = mock(classOf[CGPublisher])

      val listener = TestFSMRef(new Broker(withPublisher(cg), None), "Broker")
      watch(listener)
      listener ! Broker.Close
      assert(listener.stateName == Closed)
    }

    "terminate on FSM.StateTimeout in Opening state" in {
      val cg = mock(classOf[CGPublisher])

      val listener = TestFSMRef(new Broker(withPublisher(cg), None), "Broker")
      listener.setState(Opening)
      watch(listener)
      listener ! FSM.StateTimeout
      expectMsg(Terminated(listener))
    }
  }
}