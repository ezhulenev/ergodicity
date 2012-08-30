package com.ergodicity.core.broker

import org.scalatest.WordSpec
import com.ergodicity.core.OrderType.GoodTillCancelled
import com.ergodicity.core.{OrderDirection, Isin}
import com.ergodicity.core.Market.{Options, Futures}
import org.mockito.Mockito._
import org.mockito.Matchers._
import ru.micexrts.cgate.{MessageKeyType, Publisher}
import ru.micexrts.cgate.messages.DataMessage
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.Message
import Broker._
import Protocol._

class ActionSpec extends WordSpec {

  implicit val config = Broker.Config("000")

  "Action" must {
    "support FutAddOrder Buy" in {

      val dataMessage = mock(classOf[DataMessage])
      val publisher = mock(classOf[Publisher])
      when(publisher.newMessage(MessageKeyType.KEY_ID, Message.FutAddOrder.MSG_ID)).thenReturn(dataMessage)
      when(dataMessage.getData).thenReturn(ByteBuffer.allocate(1000))

      val buy = Buy[Futures](Isin("RTS-6.12"), 1, BigDecimal(100), GoodTillCancelled)
      val data = buy.encode(publisher).getData
      val futAddOrder = new Message.FutAddOrder(data)

      assert(futAddOrder.get_client_code() == config.clientCode, "Client code = " + futAddOrder.get_client_code())
      assert(futAddOrder.get_isin() == "RTS-6.12")
      assert(futAddOrder.get_amount() == 1)
      assert(futAddOrder.get_type() == mapOrderType(GoodTillCancelled))
      assert(futAddOrder.get_dir() == mapOrderDirection(OrderDirection.Buy))
    }

    "support OptAddOrder Buy" in {

      val dataMessage = mock(classOf[DataMessage])
      val publisher = mock(classOf[Publisher])
      when(publisher.newMessage(MessageKeyType.KEY_ID, Message.OptAddOrder.MSG_ID)).thenReturn(dataMessage)
      when(dataMessage.getData).thenReturn(ByteBuffer.allocate(1000))

      val buy = Buy[Options](Isin("RTS-6.12"), 1, BigDecimal(100), GoodTillCancelled)
      val optAddOrder = new Message.OptAddOrder(buy.encode(publisher).getData)

      assert(optAddOrder.get_client_code() == config.clientCode, "Client code = " + optAddOrder.get_client_code())
      assert(optAddOrder.get_isin() == "RTS-6.12")
      assert(optAddOrder.get_amount() == 1)
      assert(optAddOrder.get_type() == mapOrderType(GoodTillCancelled))
      assert(optAddOrder.get_dir() == mapOrderDirection(OrderDirection.Buy))
    }

    "support FutDelOrder" in {
      val dataMessage = mock(classOf[DataMessage])
      val publisher = mock(classOf[Publisher])
      when(publisher.newMessage(any(), any())).thenReturn(dataMessage)
      when(dataMessage.getData).thenReturn(ByteBuffer.allocate(1000))

      val cancel = Cancel[Futures](OrderId(1111))
      val futDelOrder = new Message.FutDelOrder(cancel.encode(publisher).getData)

      assert(futDelOrder.get_order_id() == 1111)
    }

    "support OptDelOrder" in {
      val dataMessage = mock(classOf[DataMessage])
      val publisher = mock(classOf[Publisher])
      when(publisher.newMessage(any(), any())).thenReturn(dataMessage)
      when(dataMessage.getData).thenReturn(ByteBuffer.allocate(1000))

      val cancel = Cancel[Options](OrderId(1111))
      val optDelOrder = new Message.OptDelOrder(cancel.encode(publisher).getData)

      assert(optDelOrder.get_order_id() == 1111)
    }

  }
}