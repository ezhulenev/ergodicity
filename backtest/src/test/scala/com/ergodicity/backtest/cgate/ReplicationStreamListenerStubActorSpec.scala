package com.ergodicity.backtest.cgate

import akka.actor.ActorSystem
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.event.Logging
import akka.testkit._
import com.ergodicity.backtest.cgate.ListenerStubState.Binded
import com.ergodicity.cgate.StreamEvent.{TnCommit, TnBegin, StreamData}
import com.ergodicity.cgate.{StreamEvent, DataStreamSubscriber, Active, Closed}
import java.nio.ByteBuffer
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import ru.micexrts.cgate.CGateException

class ReplicationStreamListenerStubActorSpec extends TestKit(ActorSystem("ReplicationStreamListenerStubActorSpec", com.ergodicity.engine.EngineSystemConfig)) with WordSpec with ShouldMatchers with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "DataStreamListenerStub Actor" must {
    "open listener with empty data" in {
      val subscriber = TestProbe()
      val listenerActor = TestActorRef(new ReplicationStreamListenerStubActor())
      val listenerBinding = ListenerBindingStub wrap listenerActor
      listenerBinding.bind(new DataStreamSubscriber(subscriber.ref))
      val listener = listenerBinding.listener

      listenerActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(listenerActor, Binded(Closed)))

      listener.open("Ebaka")
      subscriber.expectMsg(StreamEvent.Open)
      subscriber.expectMsg(StreamEvent.StreamOnline)
      expectMsg(Transition(listenerActor, Binded(Closed), Binded(Active)))
    }

    "open listener and dispatch data" in {
      val subscriber = TestProbe()
      val listenerActor = TestActorRef(new ReplicationStreamListenerStubActor())
      val listenerBinding = ListenerBindingStub wrap listenerActor
      listenerBinding.bind(new DataStreamSubscriber(subscriber.ref))
      val listener = listenerBinding.listener

      listenerActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(listenerActor, Binded(Closed)))

      val dataMsg = StreamData(0, ByteBuffer.allocate(0))
      listenerActor ! ReplicationStreamListenerStubActor.DispatchData(dataMsg :: Nil)

      listener.open("Ebaka")
      subscriber.expectMsg(StreamEvent.Open)
      subscriber.expectMsg(StreamEvent.TnBegin)
      subscriber.expectMsg(dataMsg)
      subscriber.expectMsg(StreamEvent.TnCommit)
      subscriber.expectMsg(StreamEvent.StreamOnline)
      expectMsg(Transition(listenerActor, Binded(Closed), Binded(Active)))
    }

    "throw exception opening Active listener" in {
      val listenerActor = TestFSMRef(new ReplicationStreamListenerStubActor())
      val listenerBinding = ListenerBindingStub wrap listenerActor
      val listener = listenerBinding.listener
      listenerActor.setState(Binded(Active))

      intercept[CGateException] {
        listener.open("Ebaka")
      }
    }

    "close Active listener" in {
      val subscriber = TestProbe()
      val listenerActor = TestFSMRef(new ReplicationStreamListenerStubActor(replState = "CloseState"))
      val listenerBinding = ListenerBindingStub wrap listenerActor
      listenerBinding.bind(new DataStreamSubscriber(subscriber.ref))
      val listener = listenerBinding.listener
      listenerActor.setState(Binded(Active))

      listenerActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(listenerActor, Binded(Active)))

      listener.close()
      subscriber.expectMsg(StreamEvent.ReplState("CloseState"))
      subscriber.expectMsg(StreamEvent.Close)
      expectMsg(Transition(listenerActor, Binded(Active), Binded(Closed)))
    }

    "fail close already Closed listener" in {
      val listenerActor = TestFSMRef(new ReplicationStreamListenerStubActor())
      val listenerBinding = ListenerBindingStub wrap listenerActor
      val listener = listenerBinding.listener

      intercept[CGateException] {
        listener.close()
      }
    }

    "get state" in {
      val listenerActor = TestFSMRef(new ReplicationStreamListenerStubActor())
      val listenerBinding = ListenerBindingStub wrap listenerActor
      val listener = listenerBinding.listener

      assert(listener.getState == Closed.value)

      listenerActor.setState(Binded(Active))
      assert(listener.getState == Active.value)
    }

    "dispatch data with opened listener" in {
      val subscriber = TestProbe()
      val listenerActor = TestFSMRef(new ReplicationStreamListenerStubActor())
      val listenerBinding = ListenerBindingStub wrap listenerActor
      listenerBinding.bind(new DataStreamSubscriber(subscriber.ref))
      listenerActor.setState(Binded(Active))

      val dataMsg = StreamData(0, ByteBuffer.allocate(0))
      listenerActor ! ReplicationStreamListenerStubActor.DispatchData(dataMsg :: Nil)

      subscriber.expectMsg(TnBegin)
      subscriber.expectMsg(dataMsg)
      subscriber.expectMsg(TnCommit)
    }
  }
}