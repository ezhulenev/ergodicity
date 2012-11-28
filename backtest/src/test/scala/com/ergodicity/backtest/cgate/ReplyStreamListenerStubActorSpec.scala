package com.ergodicity.backtest.cgate

import akka.actor.ActorSystem
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.event.Logging
import akka.testkit._
import com.ergodicity.backtest.cgate.ListenerStubState.Binded
import com.ergodicity.cgate.{Active, Closed}
import java.nio.ByteBuffer
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import ru.micexrts.cgate.CGateException
import com.ergodicity.core.broker.{ReplyEvent, ReplySubscriber}
import com.ergodicity.core.broker.ReplyEvent.ReplyData

class ReplyStreamListenerStubActorSpec extends TestKit(ActorSystem("ReplyStreamListenerStubActorSpec", com.ergodicity.engine.EngineSystemConfig)) with WordSpec with ShouldMatchers with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "ReplyStreamListenerStub Actor" must {
    "open listener with empty data" in {
      val subscriber = TestProbe()
      val listenerActor = TestActorRef(new ReplyStreamListenerStubActor())
      val listenerBinding = ListenerBindingStub wrap listenerActor
      listenerBinding.bind(new ReplySubscriber(subscriber.ref))
      val listener = listenerBinding.listener

      listenerActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(listenerActor, Binded(Closed)))

      listener.open("Ebaka")
      subscriber.expectMsg(ReplyEvent.Open)      
      expectMsg(Transition(listenerActor, Binded(Closed), Binded(Active)))      
    }

    "open listener and dispatch data" in {
      val subscriber = TestProbe()
      val listenerActor = TestActorRef(new ReplyStreamListenerStubActor())
      val listenerBinding = ListenerBindingStub wrap listenerActor
      listenerBinding.bind(new ReplySubscriber(subscriber.ref))
      val listener = listenerBinding.listener

      listenerActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(listenerActor, Binded(Closed)))

      val replyMsg = ReplyData(1, 1, ByteBuffer.allocate(0))
      listenerActor ! ReplyStreamListenerStubActor.DispatchReply(replyMsg)

      listener.open("Ebaka")
      subscriber.expectMsg(ReplyEvent.Open)
      subscriber.expectMsg(replyMsg)
      expectMsg(Transition(listenerActor, Binded(Closed), Binded(Active)))
    }

    "throw exception opening Active listener" in {
      val listenerActor = TestFSMRef(new ReplyStreamListenerStubActor())
      val listenerBinding = ListenerBindingStub wrap listenerActor
      val listener = listenerBinding.listener
      listenerActor.setState(Binded(Active))

      intercept[CGateException] {
        listener.open("Ebaka")
      }
    }

    "close Active listener" in {
      val subscriber = TestProbe()
      val listenerActor = TestFSMRef(new ReplyStreamListenerStubActor())
      val listenerBinding = ListenerBindingStub wrap listenerActor
      listenerBinding.bind(new ReplySubscriber(subscriber.ref))
      val listener = listenerBinding.listener
      listenerActor.setState(Binded(Active))

      listenerActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(listenerActor, Binded(Active)))

      listener.close()
      expectMsg(Transition(listenerActor, Binded(Active), Binded(Closed)))
    }

    "fail close already Closed listener" in {
      val listenerActor = TestFSMRef(new ReplyStreamListenerStubActor())
      val listenerBinding = ListenerBindingStub wrap listenerActor
      val listener = listenerBinding.listener

      intercept[CGateException] {
        listener.close()
      }
    }

    "get state" in {
      val listenerActor = TestFSMRef(new ReplyStreamListenerStubActor())
      val listenerBinding = ListenerBindingStub wrap listenerActor
      val listener = listenerBinding.listener

      assert(listener.getState == Closed.value)

      listenerActor.setState(Binded(Active))
      assert(listener.getState == Active.value)
    }

    "dispatch data with opened listener" in {
      val subscriber = TestProbe()
      val listenerActor = TestFSMRef(new ReplyStreamListenerStubActor())
      val listenerBinding = ListenerBindingStub wrap listenerActor
      listenerBinding.bind(new ReplySubscriber(subscriber.ref))
      listenerActor.setState(Binded(Active))

      val replyMsg = ReplyData(1, 1, ByteBuffer.allocate(0))
      listenerActor ! ReplyStreamListenerStubActor.DispatchReply(replyMsg)

      subscriber.expectMsg(replyMsg)
    }
  }
}