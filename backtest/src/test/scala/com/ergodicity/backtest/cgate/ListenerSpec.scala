package com.ergodicity.backtest.cgate

import akka.actor.ActorSystem
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.event.Logging
import akka.testkit._
import com.ergodicity.cgate.{StreamEvent, DataStreamSubscriber, Active, Closed}
import org.mockito.Mockito._
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import ru.micexrts.cgate.{CGateException, ISubscriber}
import akka.actor.FSM.Transition
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack

class ListenerSpec extends TestKit(ActorSystem("ListenerSpec")) with WordSpec with ShouldMatchers with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, classOf[ListenerSpec])

  override def afterAll() {
    system.shutdown()
  }

  "Listener" must {
    "open listener" in {
      val subscriber = TestProbe()
      val listenerActor = TestActorRef(new ListenerActor(new DataStreamSubscriber(subscriber.ref)))
      val listener = Listener(listenerActor)

      listenerActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(listenerActor, Closed))

      listener.open("Ebaka")
      subscriber.expectMsg(StreamEvent.Open)
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