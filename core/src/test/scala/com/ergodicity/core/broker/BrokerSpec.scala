package com.ergodicity.core.broker

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.{Isin, OrderType, AkkaConfigurations}
import akka.actor.{FSM, ActorSystem}
import akka.pattern.ask
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import akka.util.Timeout
import akka.util.duration._
import ru.micexrts.cgate.{Publisher => CGPublisher, MessageKeyType}
import org.mockito.Mockito._
import org.mockito.Matchers._
import com.ergodicity.cgate.{scheme, Active, Closed, Opening}
import com.ergodicity.core.Market.Futures
import ru.micexrts.cgate.messages.DataMessage
import java.nio.ByteBuffer
import Broker._
import Protocol._
import com.ergodicity.core.broker.ReplyEvent.{ReplyData, TimeoutMessage}
import com.ergodicity.cgate.scheme.Message
import akka.dispatch.Await

class BrokerSpec extends TestKit(ActorSystem("BrokerSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)


  implicit val timeout = Timeout(5 seconds)

  override def afterAll() {
    system.shutdown()
  }

  implicit val config = Broker.Config("000")

  "Broker" must {
    "be initialized in Closed state" in {
      val cg = mock(classOf[CGPublisher])

      val broker = TestFSMRef(new Broker(cg, None), "Broker")
      log.info("State: " + broker.stateName)
      assert(broker.stateName == Closed)
    }

    "fail on Publisher gone to Error state" in {
      val cg = mock(classOf[CGPublisher])

      val broker = TestFSMRef(new Broker(cg, None), "Broker")
      intercept[BrokerError] {
        broker receive PublisherState(com.ergodicity.cgate.Error)
      }
    }

    "return to Closed state after Close broker sent" in {
      val cg = mock(classOf[CGPublisher])

      val broker = TestFSMRef(new Broker(cg, None), "Broker")
      watch(broker)
      broker ! Broker.Close
      assert(broker.stateName == Closed)
    }

    "fail on FSM.StateTimeout in Opening state" in {
      val cg = mock(classOf[CGPublisher])

      val broker = TestFSMRef(new Broker(cg, None), "Broker")
      broker.setState(Opening)

      intercept[OpenTimedOut] {
        broker receive FSM.StateTimeout
      }
    }

    "execute market command" in {
      val data = ByteBuffer.allocate(1000)
      val dataMessage = mock(classOf[DataMessage])
      when(dataMessage.getData).thenReturn(data)

      val publisher = mock(classOf[CGPublisher])
      when(publisher.newMessage(MessageKeyType.KEY_ID, com.ergodicity.cgate.scheme.Message.FutAddOrder.MSG_ID)).thenReturn(dataMessage)

      val broker = TestFSMRef(new Broker(publisher, None), "Broker")
      broker.setState(Active)

      broker ! Buy[Futures](Isin("isin"), 1, BigDecimal(100), OrderType.GoodTillCancelled)

      Thread.sleep(100)

      verify(publisher).newMessage(MessageKeyType.KEY_ID, com.ergodicity.cgate.scheme.Message.FutAddOrder.MSG_ID)
      verify(dataMessage).setUserId(1)

      val futAddOrder = new scheme.Message.FutAddOrder(data)
      assert(futAddOrder.get_isin() == "isin")
      assert(futAddOrder.get_amount() == 1)
      assert(futAddOrder.get_client_code() == config.clientCode, "Client code = " + futAddOrder.get_client_code())
      assert(futAddOrder.get_price() == "100")
    }

    "handle Timeout failures" in {
      val publisher = mockPublisher
      val broker = TestFSMRef(new Broker(publisher, None), "Broker")
      broker.setState(Active)

      val response = broker ? Buy[Futures](Isin("isin"), 1, BigDecimal(100), OrderType.GoodTillCancelled)
      broker ! TimeoutMessage(1)

      intercept[BrokerException] {
        Await.result(response, 1.second)
      }
    }

    "handle Flood failures" in {
      val data = ByteBuffer.allocate(1000)

      val errorMsg = new Message.FORTS_MSG99(data)
      errorMsg.set_queue_size(10)
      errorMsg.set_penalty_remain(50)
      errorMsg.set_message("Flood")

      val publisher = mockPublisher
      val broker = TestFSMRef(new Broker(publisher, None), "Broker")
      broker.setState(Active)

      val response = broker ? Buy[Futures](Isin("isin"), 1, BigDecimal(100), OrderType.GoodTillCancelled)
      broker ! ReplyData(1, 99, errorMsg.getData)

      intercept[FloodException] {
        Await.result(response, 1.second)
      }
    }

    "handle Error failures" in {
      val data = ByteBuffer.allocate(1000)

      val errorMsg = new Message.FORTS_MSG100(data)
      errorMsg.set_message("Error")

      val publisher = mockPublisher
      val broker = TestFSMRef(new Broker(publisher, None), "Broker")
      broker.setState(Active)

      val response = broker ? Buy[Futures](Isin("isin"), 1, BigDecimal(100), OrderType.GoodTillCancelled)
      broker ! ReplyData(1, 100, errorMsg.getData)

      intercept[BrokerErrorException] {
        Await.result(response, 1.second)
      }
    }

    "handle order response" in {
      val data = ByteBuffer.allocate(1000)

      val orderMsg = new Message.FORTS_MSG101(data)
      orderMsg.set_order_id(111)

      val publisher = mockPublisher
      val broker = TestFSMRef(new Broker(publisher, None), "Broker")
      broker.setState(Active)

      broker ! Buy[Futures](Isin("isin"), 1, BigDecimal(100), OrderType.GoodTillCancelled)
      broker ! ReplyData(1, 101, orderMsg.getData)

      expectMsg(OrderId(111))
    }
  }

  def mockPublisher = {
    val publisher = mock(classOf[CGPublisher])
    val dataMsg = mock(classOf[DataMessage])
    when(publisher.newMessage(any(), any())).thenReturn(dataMsg)
    when(dataMsg.getData).thenReturn(ByteBuffer.allocate(1000))
    publisher
  }
}