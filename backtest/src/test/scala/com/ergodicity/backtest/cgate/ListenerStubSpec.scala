package com.ergodicity.backtest.cgate

import akka.actor.ActorSystem
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.event.Logging
import akka.testkit._
import com.ergodicity.cgate.{StreamEvent, DataStreamSubscriber, Active, Closed}
import org.mockito.Mockito._
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import ru.micexrts.cgate.{CGateException, ISubscriber}
import java.nio.ByteBuffer
import com.ergodicity.cgate.StreamEvent.{TnCommit, TnBegin, StreamData}

class ListenerStubSpec extends TestKit(ActorSystem("ListenerStubSpec")) with WordSpec with ShouldMatchers with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, classOf[ListenerStubSpec])

  override def afterAll() {
    system.shutdown()
  }

  "Listener" must {
    "open listener with empty data" in {
      val subscriber = TestProbe()
      val listenerActor = TestActorRef(new ListenerStubActor(new DataStreamSubscriber(subscriber.ref)))
      val listener = ListenerStub wrap listenerActor

      listenerActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(listenerActor, Closed))

      listener.open("Ebaka")
      subscriber.expectMsg(StreamEvent.Open)
      subscriber.expectMsg(StreamEvent.StreamOnline)
      expectMsg(Transition(listenerActor, Closed, Active))
    }

    "open listener and dispatch data" in {
      val subscriber = TestProbe()
      val listenerActor = TestActorRef(new ListenerStubActor(new DataStreamSubscriber(subscriber.ref)))
      val listener = ListenerStub wrap listenerActor

      listenerActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(listenerActor, Closed))

      val dataMsg = StreamData(0, ByteBuffer.allocate(0))
      listenerActor ! ListenerStubActor.Dispatch(dataMsg :: Nil)

      listener.open("Ebaka")
      subscriber.expectMsg(StreamEvent.Open)
      subscriber.expectMsg(StreamEvent.TnBegin)
      subscriber.expectMsg(dataMsg)
      subscriber.expectMsg(StreamEvent.TnCommit)
      subscriber.expectMsg(StreamEvent.StreamOnline)
      expectMsg(Transition(listenerActor, Closed, Active))
    }

    "throw exception opening Active listener" in {
      val listenerActor = TestFSMRef(new ListenerStubActor(mock(classOf[ISubscriber])))
      listenerActor.setState(Active)
      val listener = ListenerStub wrap listenerActor

      intercept[CGateException] {
        listener.open("Ebaka")
      }
    }

    "close Active listener" in {
      val subscriber = TestProbe()
      val listenerActor = TestFSMRef(new ListenerStubActor(new DataStreamSubscriber(subscriber.ref), replState = "CloseState"))
      listenerActor.setState(Active)
      val listener = ListenerStub wrap listenerActor

      listenerActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(listenerActor, Active))

      listener.close()
      subscriber.expectMsg(StreamEvent.ReplState("CloseState"))
      subscriber.expectMsg(StreamEvent.Close)
      expectMsg(Transition(listenerActor, Active, Closed))
    }

    "fail close already Closed listener" in {
      val listenerActor = TestFSMRef(new ListenerStubActor(mock(classOf[ISubscriber])))
      val listener = ListenerStub wrap listenerActor

      intercept[CGateException] {
        listener.close()
      }
    }

    "get state" in {
      val listenerActor = TestFSMRef(new ListenerStubActor(mock(classOf[ISubscriber])))
      val listener = ListenerStub wrap listenerActor

      assert(listener.getState == Closed.value)

      listenerActor.setState(Active)
      assert(listener.getState == Active.value)
    }

    "dispatch data with opened listener" in {
      val subscriber = TestProbe()
      val listenerActor = TestFSMRef(new ListenerStubActor(new DataStreamSubscriber(subscriber.ref)))
      listenerActor.setState(Active)

      val dataMsg = StreamData(0, ByteBuffer.allocate(0))
      listenerActor ! ListenerStubActor.Dispatch(dataMsg :: Nil)

      subscriber.expectMsg(TnBegin)
      subscriber.expectMsg(dataMsg)
      subscriber.expectMsg(TnCommit)
    }
  }
}