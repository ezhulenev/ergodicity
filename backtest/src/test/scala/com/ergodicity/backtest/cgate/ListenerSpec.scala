package com.ergodicity.backtest.cgate

import akka.actor.ActorSystem
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.event.Logging
import akka.testkit.{TestFSMRef, TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.cgate.{Active, Closed}
import org.mockito.Mockito._
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import ru.micexrts.cgate.{CGateException, ISubscriber}

class ListenerSpec extends TestKit(ActorSystem("ListenerSpec")) with WordSpec with ShouldMatchers with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, classOf[ListenerSpec])

  override def afterAll() {
    system.shutdown()
  }

  "Listener" must {
    "open listener" in {
      val listenerActor = TestActorRef(new ListenerActor(mock(classOf[ISubscriber])))
      val listener = Listener(listenerActor)

      listenerActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(listenerActor, Closed))

      listener.open("Ebaka")
      expectMsg(Transition(listenerActor, Closed, Active))
    }

    "throw exception opening Active listener" in {
      val listenerActor = TestFSMRef(new ListenerActor(mock(classOf[ISubscriber])))
      listenerActor.setState(Active)
      val listener = Listener(listenerActor)

      intercept[CGateException] {
        listener.open("Ebaka")
      }
    }
  }
}