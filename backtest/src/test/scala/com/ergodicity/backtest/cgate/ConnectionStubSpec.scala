package com.ergodicity.backtest.cgate

import akka.actor.{FSM, ActorSystem}
import akka.event.Logging
import akka.testkit._
import com.ergodicity.cgate._
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import ru.micexrts.cgate.CGateException
import akka.actor.FSM.Transition
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack

class ConnectionStubSpec extends TestKit(ActorSystem("ConnectionStubSpec")) with WordSpec with ShouldMatchers with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, classOf[ConnectionStubSpec])

  override def afterAll() {
    system.shutdown()
  }

  "Connection" must {
    "open connection" in {
      val connectionActor = TestActorRef(new ConnectionStubActor())
      val connection = ConnectionStub wrap connectionActor

      connectionActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(connectionActor, Closed))

      connection.open("Ebaka")
      expectMsg(Transition(connectionActor, Closed, Opening))
      
      connectionActor ! FSM.StateTimeout
      expectMsg(Transition(connectionActor, Opening, Active))
    }

    "throw exception opening Active connection" in {
      val connectionActor = TestFSMRef(new ConnectionStubActor())
      connectionActor.setState(Active)
      val connection = ConnectionStub wrap connectionActor

      intercept[CGateException] {
        connection.open("Ebaka")
      }
    }

    "close Active connection" in {
      val connectionActor = TestFSMRef(new ConnectionStubActor())
      val connection = ConnectionStub wrap connectionActor

      connectionActor.setState(Active)

      connectionActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(connectionActor, Active))

      connection.close()
      expectMsg(Transition(connectionActor, Active, Closed))
    }

    "fail close already Closed connection" in {
      val connectionActor = TestFSMRef(new ConnectionStubActor())
      val connection = ConnectionStub wrap connectionActor

      connectionActor.setState(Closed)

      intercept[CGateException] {
        connection.close()
      }
    }

    "get state" in {
      val connectionActor = TestFSMRef(new ConnectionStubActor())
      val connection = ConnectionStub wrap connectionActor

      assert(connection.getState == Closed.value)

      connectionActor.setState(Active)
      assert(connection.getState == Active.value)
    }

  }
}
