package com.ergodicity.core.broker

import org.scalatest.WordSpec
import com.ergodicity.core.common.OrderType.GoodTillCancelled
import com.ergodicity.core.common.Isin
import com.ergodicity.core.broker.Market.{Options, Futures}
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
      val futAddOrder = new Message.FutAddOrder(buy.command.send(buy, publisher).getData)

      assert(futAddOrder.get_isin() == "RTS-6.12")
      assert(futAddOrder.get_amount() == 1)
    }

    "support OptAddOrder Buy" in {

      val dataMessage = mock(classOf[DataMessage])
      val publisher = mock(classOf[Publisher])
      when(publisher.newMessage(any(), any())).thenReturn(dataMessage)
      when(dataMessage.getData).thenReturn(ByteBuffer.allocate(1000))

      val buy = Buy[Options](Isin("RTS-6.12"), 1, BigDecimal(100), GoodTillCancelled)
      val futOptOrder = new Message.OptAddOrder(buy.command.send(buy, publisher).getData)

      assert(futOptOrder.get_isin() == "RTS-6.12")
      assert(futOptOrder.get_amount() == 1)
    }
  }
}