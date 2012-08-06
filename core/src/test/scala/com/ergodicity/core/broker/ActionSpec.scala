package com.ergodicity.core.broker

import org.scalatest.WordSpec
import com.ergodicity.core.OrderType.GoodTillCancelled
import com.ergodicity.core.Isin
import com.ergodicity.core.Market.{Options, Futures}
import org.mockito.Mockito._
import org.mockito.Matchers._
import ru.micexrts.cgate.Publisher
import ru.micexrts.cgate.messages.DataMessage
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.Message
import Broker._
import Protocol._

class ActionSpec extends WordSpec {

  "Action" must {
    "support FutAddOrder Buy" in {

      val dataMessage = mock(classOf[DataMessage])
      val publisher = mock(classOf[Publisher])
      when(publisher.newMessage(any(), any())).thenReturn(dataMessage)
      when(dataMessage.getData).thenReturn(ByteBuffer.allocate(1000))

      val buy = Buy[Futures](Isin("RTS-6.12"), 1, BigDecimal(100), GoodTillCancelled)
      val futAddOrder = new Message.FutAddOrder(buy.encode(publisher).getData)

      assert(futAddOrder.get_isin() == "RTS-6.12")
      assert(futAddOrder.get_amount() == 1)
    }

    "support OptAddOrder Buy" in {

      val dataMessage = mock(classOf[DataMessage])
      val publisher = mock(classOf[Publisher])
      when(publisher.newMessage(any(), any())).thenReturn(dataMessage)
      when(dataMessage.getData).thenReturn(ByteBuffer.allocate(1000))

      val buy = Buy[Options](Isin("RTS-6.12"), 1, BigDecimal(100), GoodTillCancelled)
      val futOptOrder = new Message.OptAddOrder(buy.encode(publisher).getData)

      assert(futOptOrder.get_isin() == "RTS-6.12")
      assert(futOptOrder.get_amount() == 1)
    }

    "support FutDelOrder" in {
      val dataMessage = mock(classOf[DataMessage])
      val publisher = mock(classOf[Publisher])
      when(publisher.newMessage(any(), any())).thenReturn(dataMessage)
      when(dataMessage.getData).thenReturn(ByteBuffer.allocate(1000))

      val cancel = Cancel[Futures](Order(1111))
      val futDelOrder = new Message.FutDelOrder(cancel.encode(publisher).getData)

      assert(futDelOrder.get_order_id() == 1111)
    }

    "support OptDelOrder" in {
      val dataMessage = mock(classOf[DataMessage])
      val publisher = mock(classOf[Publisher])
      when(publisher.newMessage(any(), any())).thenReturn(dataMessage)
      when(dataMessage.getData).thenReturn(ByteBuffer.allocate(1000))

      val cancel = Cancel[Options](Order(1111))
      val optDelOrder = new Message.OptDelOrder(cancel.encode(publisher).getData)

      assert(optDelOrder.get_order_id() == 1111)
    }

  }
}