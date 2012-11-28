package com.ergodicity.backtest.cgate

import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.{FSM, ActorSystem}
import akka.event.Logging
import akka.testkit._
import com.ergodicity.cgate._
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import ru.micexrts.cgate.{MessageKeyType, Publisher, CGateException}
import org.mockito.Mockito._
import akka.actor.FSM.Transition
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import ru.micexrts.cgate.messages.DataMessage
import com.ergodicity.cgate.scheme.Message
import java.nio.ByteBuffer
import com.ergodicity.core.broker.Broker._
import akka.actor.FSM.Transition
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.core.Market.Futures
import com.ergodicity.core.{OrderDirection, Isin}
import com.ergodicity.core.OrderType.GoodTillCancelled
import com.ergodicity.core.broker.Protocol._
import akka.actor.FSM.Transition
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.core.broker.Broker

class PublisherStubSpec extends TestKit(ActorSystem("PublisherStubSpec", com.ergodicity.engine.EngineSystemConfig)) with WordSpec with ShouldMatchers with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, classOf[PublisherStubSpec])

  override def afterAll() {
    system.shutdown()
  }

  "Publisher Stub Actor" must {
    "open publisher" in {
      val publisherActor = TestActorRef(new PublisherStubActor())
      val publisher = PublisherStub wrap publisherActor

      publisherActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(publisherActor, Closed))

      publisher.open("Ebaka")
      expectMsg(Transition(publisherActor, Closed, Opening))

      publisherActor ! FSM.StateTimeout
      expectMsg(Transition(publisherActor, Opening, Active))
    }

    "throw exception opening Active publisher" in {
      val publisherActor = TestFSMRef(new PublisherStubActor())
      publisherActor.setState(Active)
      val publisher = PublisherStub wrap publisherActor

      intercept[CGateException] {
        publisher.open("Ebaka")
      }
    }

    "close Active publisher" in {
      val publisherActor = TestFSMRef(new PublisherStubActor())
      val publisher = PublisherStub wrap publisherActor

      publisherActor.setState(Active)

      publisherActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(publisherActor, Active))

      publisher.close()
      expectMsg(Transition(publisherActor, Active, Closed))
    }

    "fail close already Closed publisher" in {
      val publisherActor = TestFSMRef(new PublisherStubActor())
      val publisher = PublisherStub wrap publisherActor

      publisherActor.setState(Closed)

      intercept[CGateException] {
        publisher.close()
      }
    }

    "get state" in {
      val publisherActor = TestFSMRef(new PublisherStubActor())
      val publisher = PublisherStub wrap publisherActor

      assert(publisher.getState == Closed.value)

      publisherActor.setState(Active)
      assert(publisher.getState == Active.value)
    }
  }

  "Publisher Stub" must {
    implicit val config = Broker.Config("000")

    "support Action serialization" in {
      val publisher = PublisherStub wrap system.deadLetters

      val buy = Buy[Futures](Isin("RTS-6.12"), 1, BigDecimal(100), GoodTillCancelled)
      val dataMessage = buy.encode(publisher)
      assert(dataMessage.getMsgId == Message.FutAddOrder.MSG_ID)

      val data = dataMessage.getData
      val futAddOrder = new Message.FutAddOrder(data)

      assert(futAddOrder.get_client_code() == config.clientCode, "Client code = " + futAddOrder.get_client_code())
      assert(futAddOrder.get_isin() == "RTS-6.12")
      assert(futAddOrder.get_amount() == 1)
      assert(futAddOrder.get_type() == mapOrderType(GoodTillCancelled))
      assert(futAddOrder.get_dir() == mapOrderDirection(OrderDirection.Buy))
    }
  }
}
